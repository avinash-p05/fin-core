package com.fincore.notification;

import com.fincore.events.PaymentEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Downstream consumer of payment events. It runs in the same process for simplicity but only
 * writes its own tables, so it could be extracted into a separate service without code changes.
 * Exceptions propagate to the container's error handler (bounded retries, then DLT).
 */
@Component
@ConditionalOnProperty(prefix = "fincore.consumer", name = "enabled", havingValue = "true")
class PaymentEventListener {

    private final PaymentEventHandler handler;
    private final JsonMapper json;

    PaymentEventListener(PaymentEventHandler handler, JsonMapper json) {
        this.handler = handler;
        this.json = json;
    }

    @KafkaListener(topics = "${fincore.kafka.payment-events-topic}", groupId = "fincore-notifications")
    void onMessage(ConsumerRecord<String, String> record) {
        PaymentEvent event = json.readValue(record.value(), PaymentEvent.class);
        handler.handle(event);
    }
}
