package com.fincore.outbox;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Relays committed outbox rows to Kafka.
 *
 * <p>Delivery is at-least-once: if the process dies after Kafka acknowledged a record but before
 * this transaction commits the PUBLISHED status, the row is sent again on the next run. Consumers
 * must therefore be idempotent (they de-duplicate on the eventId header/payload field).
 */
@Component
public class OutboxPublisher {

    public static final String HEADER_EVENT_ID = "eventId";
    public static final String HEADER_EVENT_TYPE = "eventType";

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final OutboxProperties props;
    private final String topic;
    private final Clock clock;

    public OutboxPublisher(OutboxRepository outbox, KafkaTemplate<String, String> kafka, OutboxProperties props,
                           @Value("${fincore.kafka.payment-events-topic}") String topic, Clock clock) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.props = props;
        this.topic = topic;
        this.clock = clock;
    }

    /**
     * Publishes one batch of due events and returns how many were successfully published. Stops at
     * the first failure: if Kafka is down, the remaining rows stay untouched instead of each
     * blocking for the producer timeout while this transaction holds their row locks.
     */
    @Transactional
    public int publishBatch() {
        List<OutboxEvent> due = outbox.lockDueEvents(clock.instant(), props.batchSize());
        int published = 0;
        for (OutboxEvent event : due) {
            if (!publish(event)) {
                break;
            }
            published++;
        }
        return published;
    }

    boolean publish(OutboxEvent event) {
        // Key = payment id: all events of one payment land on the same partition, in send order.
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, event.getAggregateId().toString(), event.getPayload());
        record.headers().add(HEADER_EVENT_ID, event.getId().toString().getBytes(UTF_8));
        record.headers().add(HEADER_EVENT_TYPE, event.getEventType().getBytes(UTF_8));
        try {
            kafka.send(record).get(props.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
            event.markPublished(clock.instant());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(event, e);
        } catch (Exception e) {
            fail(event, e);
        }
        return false;
    }

    private void fail(OutboxEvent event, Exception e) {
        Instant now = clock.instant();
        event.markAttemptFailed(e.toString(), now, props.maxAttempts(), props.initialBackoff(), props.maxBackoff());
        if (event.getStatus() == OutboxStatus.FAILED) {
            log.error("Outbox event {} parked as FAILED after {} attempts", event.getId(), event.getAttempts(), e);
        } else {
            log.warn("Outbox event {} attempt {} failed; retry at {}", event.getId(), event.getAttempts(),
                    event.getNextAttemptAt());
        }
    }
}
