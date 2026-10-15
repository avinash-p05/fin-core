package com.fincore.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Integration event published to Kafka through the outbox. Amounts are decimal strings so no
 * consumer ever parses money as a floating-point number.
 *
 * @param eventId unique per event; consumers de-duplicate on it
 */
public record PaymentEvent(
        UUID eventId,
        String eventType,
        Instant occurredAt,
        UUID paymentId,
        UUID sourceAccountId,
        UUID destinationAccountId,
        String amount,
        String currency,
        UUID refundId) {

    public static final String PAYMENT_COMPLETED = "PaymentCompleted";
    public static final String PAYMENT_REFUNDED = "PaymentRefunded";
}
