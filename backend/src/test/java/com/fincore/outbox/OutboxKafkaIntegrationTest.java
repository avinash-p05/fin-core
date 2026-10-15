package com.fincore.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fincore.account.Account;
import com.fincore.events.PaymentEvent;
import com.fincore.notification.PaymentEventHandler;
import com.fincore.notification.PaymentNotificationRepository;
import com.fincore.payment.Payment;
import com.fincore.payment.PaymentDtos.RefundRequest;
import com.fincore.payment.RefundService;
import com.fincore.support.IntegrationTest;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Outbox → real Kafka → consumer → PostgreSQL, including duplicates and dead-lettering. */
class OutboxKafkaIntegrationTest extends IntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(30);

    @Autowired
    OutboxPublisher publisher;

    @Autowired
    OutboxRepository outbox;

    @Autowired
    PaymentNotificationRepository notifications;

    @Autowired
    RefundService refunds;

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    JsonMapper json;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    KafkaConnectionDetails kafkaConnection;

    @Value("${fincore.kafka.payment-events-topic}")
    String topic;

    @Test
    void outboxEventIsWrittenOnlyWhenTheTransferCommits() {
        Account payer = newAccount("1000.00");
        Account payee = newAccount("0");

        Payment completed = pay(payer, payee, "250.00");
        Payment failed = pay(payer, payee, "5000.00");

        List<OutboxEvent> events = outbox.findByAggregateIdOrderByCreatedAt(completed.getId());
        assertThat(events).singleElement().satisfies(e -> {
            assertThat(e.getEventType()).isEqualTo(PaymentEvent.PAYMENT_COMPLETED);
            assertThat(e.getStatus()).isEqualTo(OutboxStatus.PENDING);
            PaymentEvent payload = json.readValue(e.getPayload(), PaymentEvent.class);
            assertThat(payload.amount()).isEqualTo("250.00");
            assertThat(payload.eventId()).isEqualTo(e.getId());
        });
        assertThat(outbox.findByAggregateIdOrderByCreatedAt(failed.getId())).isEmpty();

        UUID[] rolledBack = new UUID[1];
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            rolledBack[0] = pay(payer, payee, "10.00").getId();
            throw new IllegalStateException("crash before commit");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(outbox.findByAggregateIdOrderByCreatedAt(rolledBack[0])).isEmpty();
    }

    @Test
    void publishedEventIsConsumedAndDuplicatesHaveNoSecondEffect() {
        Account payer = newAccount("1000.00");
        Account payee = newAccount("0");
        Payment payment = pay(payer, payee, "75.00");
        OutboxEvent event = outbox.findByAggregateIdOrderByCreatedAt(payment.getId()).getFirst();

        drainOutbox();
        assertThat(outbox.findById(event.getId()).orElseThrow().getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        await().atMost(WAIT).until(() -> notifications.countByEventId(event.getId()) == 2);

        // Duplicate publication: simulate a crash after Kafka acked but before PUBLISHED committed.
        jdbc.update("UPDATE outbox_events SET status = 'PENDING', published_at = NULL WHERE id = ?", event.getId());
        drainOutbox();
        // Duplicate delivery: the same record sent twice more by hand.
        kafka.send(topic, payment.getId().toString(), event.getPayload()).join();
        kafka.send(topic, payment.getId().toString(), event.getPayload()).join();

        // Barrier: a later event on the same key/partition is processed after the duplicates.
        UUID sentinel = sendSentinel(payment, payer, payee);
        await().atMost(WAIT).until(() -> notifications.countByEventId(sentinel) == 2);

        assertThat(notifications.countByEventId(event.getId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_events WHERE consumer = ? AND event_id = ?",
                Integer.class, PaymentEventHandler.CONSUMER_NAME, event.getId())).isEqualTo(1);
    }

    @Test
    void refundEventReachesTheConsumer() {
        Account payer = newAccount("1000.00");
        Account payee = newAccount("0");
        Payment payment = pay(payer, payee, "120.00");
        refunds.refund(payment.getId(), newKey(), new RefundRequest(new BigDecimal("20.00"), "partial"));

        drainOutbox();

        await().atMost(WAIT).until(() -> notifications.findTop50ByAccountIdOrderByCreatedAtDesc(payer.getId()).stream()
                .anyMatch(n -> n.getType().equals("REFUND_CREDIT") && n.getAmount().compareTo(new BigDecimal("20.00")) == 0));
    }

    @Test
    void malformedEventGoesStraightToDeadLetterTopic() {
        String key = "poison-" + UUID.randomUUID();
        kafka.send(topic, key, "{not valid json").join();

        assertThat(awaitDeadLetter(key).value()).isEqualTo("{not valid json");
    }

    @Test
    void persistentProcessingFailureIsRetriedThenDeadLettered() {
        // References an account that does not exist: the notification insert violates a foreign key
        // on every attempt (a retryable DataAccessException), so retries are exhausted.
        UUID eventId = UUID.randomUUID();
        PaymentEvent bad = new PaymentEvent(eventId, PaymentEvent.PAYMENT_COMPLETED, Instant.now(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), "1.00", "INR", null);
        String key = "bad-" + eventId;
        kafka.send(topic, key, json.writeValueAsString(bad)).join();

        ConsumerRecord<String, String> dead = awaitDeadLetter(key);
        assertThat(dead.value()).contains(eventId.toString());
        // Each failed attempt rolled back its de-duplication marker together with the effect.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_events WHERE event_id = ?", Integer.class, eventId)).isZero();
    }

    private void drainOutbox() {
        while (publisher.publishBatch() > 0) {
            // keep publishing until nothing is due
        }
    }

    private UUID sendSentinel(Payment payment, Account payer, Account payee) {
        UUID id = UUID.randomUUID();
        PaymentEvent sentinel = new PaymentEvent(id, PaymentEvent.PAYMENT_COMPLETED, Instant.now(), payment.getId(),
                payer.getId(), payee.getId(), "0.01", "INR", null);
        kafka.send(new ProducerRecord<>(topic, payment.getId().toString(), json.writeValueAsString(sentinel))).join();
        return id;
    }

    private ConsumerRecord<String, String> awaitDeadLetter(String key) {
        Map<String, Object> props = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, String.join(",", kafkaConnection.getBootstrapServers()),
                ConsumerConfig.GROUP_ID_CONFIG, "dlt-test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic + ".DLT"));
            long deadline = System.nanoTime() + WAIT.toNanos();
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(500))) {
                    if (key.equals(r.key())) {
                        return r;
                    }
                }
            }
        }
        throw new AssertionError("No dead-letter record with key " + key + " within " + WAIT);
    }
}
