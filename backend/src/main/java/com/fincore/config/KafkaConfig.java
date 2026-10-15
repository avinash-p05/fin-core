package com.fincore.config;

import java.time.Duration;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaConfig {

    public static final String DLT_SUFFIX = ".DLT";

    @Bean
    NewTopic paymentEventsTopic(@Value("${fincore.kafka.payment-events-topic}") String topic) {
        return TopicBuilder.name(topic).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic paymentEventsDeadLetterTopic(@Value("${fincore.kafka.payment-events-topic}") String topic) {
        return TopicBuilder.name(topic + DLT_SUFFIX).partitions(1).replicas(1).build();
    }

    /**
     * Bounded retries, then dead-letter. Transient failures (e.g. database briefly unavailable)
     * are retried {@code max-retries} times in-process; malformed events are not retryable and go
     * straight to {@code <topic>.DLT} with the original record and exception headers.
     */
    @Bean
    CommonErrorHandler kafkaErrorHandler(KafkaOperations<Object, Object> template,
                                         @Value("${fincore.consumer.max-retries}") long maxRetries,
                                         @Value("${fincore.consumer.retry-backoff}") Duration backoff) {
        var recoverer = new DeadLetterPublishingRecoverer(template,
                (record, ex) -> new TopicPartition(record.topic() + DLT_SUFFIX, -1));
        var handler = new DefaultErrorHandler(recoverer, new FixedBackOff(backoff.toMillis(), maxRetries));
        handler.addNotRetryableExceptions(tools.jackson.core.JacksonException.class, IllegalArgumentException.class);
        return handler;
    }
}
