package com.fincore.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

/** Retry, backoff and parking logic of the outbox relay, with Kafka mocked. */
class OutboxPublisherTest {

    private static final Instant NOW = Instant.parse("2026-10-15T10:00:00Z");

    private OutboxRepository repository;
    private KafkaTemplate<String, String> kafka;
    private OutboxPublisher publisher;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repository = mock(OutboxRepository.class);
        kafka = mock(KafkaTemplate.class);
        OutboxProperties props = new OutboxProperties(false, Duration.ofSeconds(1), 50, 3,
                Duration.ofSeconds(1), Duration.ofSeconds(30), Duration.ofSeconds(1));
        publisher = new OutboxPublisher(repository, kafka, props, "payments", Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void successfulSendMarksPublishedWithEventHeaders() {
        OutboxEvent event = event();
        when(repository.lockDueEvents(any(), anyInt())).thenReturn(List.of(event));
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        assertThat(publisher.publishBatch()).isEqualTo(1);

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(event.getPublishedAt()).isEqualTo(NOW);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka).send(captor.capture());
        ProducerRecord<String, String> sent = captor.getValue();
        assertThat(sent.key()).isEqualTo(event.getAggregateId().toString());
        assertThat(new String(sent.headers().lastHeader(OutboxPublisher.HEADER_EVENT_ID).value(), StandardCharsets.UTF_8))
                .isEqualTo(event.getId().toString());
    }

    @Test
    void failedSendSchedulesRetryWithExponentialBackoffThenParksAsFailed() {
        OutboxEvent event = event();
        when(repository.lockDueEvents(any(), anyInt())).thenReturn(List.of(event));
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

        publisher.publishBatch();
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getAttempts()).isEqualTo(1);
        assertThat(event.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(event.getLastError()).contains("broker down");

        publisher.publishBatch();
        assertThat(event.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(2));

        publisher.publishBatch(); // third attempt == maxAttempts
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(event.getAttempts()).isEqualTo(3);
    }

    @Test
    void batchStopsAtFirstFailureLeavingTheRestUntouched() {
        OutboxEvent first = event();
        OutboxEvent second = event();
        when(repository.lockDueEvents(any(), anyInt())).thenReturn(List.of(first, second));
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.failedFuture(new RuntimeException("timeout")));

        assertThat(publisher.publishBatch()).isZero();

        verify(kafka, times(1)).send(any(ProducerRecord.class));
        assertThat(first.getAttempts()).isEqualTo(1);
        assertThat(second.getAttempts()).isZero();
        assertThat(second.getStatus()).isEqualTo(OutboxStatus.PENDING);
    }

    @Test
    void batchIsPipelinedAndAPartialFailureOnlyAffectsThatEvent() {
        OutboxEvent first = event();
        OutboxEvent second = event();
        OutboxEvent third = event();
        when(repository.lockDueEvents(any(), anyInt())).thenReturn(List.of(first, second, third));
        // Not yet failed when handed back by send(); the broker error surfaces only when awaited.
        CompletableFuture<SendResult<String, String>> slowFailure = new CompletableFuture<>() {
            @Override
            public SendResult<String, String> get(long timeout, java.util.concurrent.TimeUnit unit)
                    throws java.util.concurrent.ExecutionException {
                throw new java.util.concurrent.ExecutionException(new RuntimeException("not enough replicas"));
            }
        };
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)))
                .thenReturn(slowFailure)
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        assertThat(publisher.publishBatch()).isEqualTo(2);

        verify(kafka, times(3)).send(any(ProducerRecord.class));
        assertThat(first.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(second.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(second.getAttempts()).isEqualTo(1);
        assertThat(third.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
    }

    @Test
    void backoffIsCappedAtMaxBackoff() {
        OutboxEvent event = event();
        for (int i = 0; i < 8; i++) {
            event.markAttemptFailed("x", NOW, 100, Duration.ofSeconds(1), Duration.ofSeconds(30));
        }
        assertThat(event.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(30));
    }

    private static OutboxEvent event() {
        return new OutboxEvent(UUID.randomUUID(), "Payment", UUID.randomUUID(), "PaymentCompleted", "{}", NOW);
    }
}
