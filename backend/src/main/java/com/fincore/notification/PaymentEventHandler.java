package com.fincore.notification;

import java.math.BigDecimal;
import java.util.UUID;

import com.fincore.events.PaymentEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotent business handler for payment events.
 *
 * <p>The de-duplication marker ({@code processed_events}) and the business effect (notifications)
 * are written in ONE database transaction. A redelivered event finds the marker and is skipped;
 * if processing fails half-way, both roll back and the redelivery processes it cleanly.
 */
@Service
public class PaymentEventHandler {

    public static final String CONSUMER_NAME = "notification-service";

    private static final Logger log = LoggerFactory.getLogger(PaymentEventHandler.class);

    private final JdbcTemplate jdbc;
    private final PaymentNotificationRepository notifications;

    public PaymentEventHandler(JdbcTemplate jdbc, PaymentNotificationRepository notifications) {
        this.jdbc = jdbc;
        this.notifications = notifications;
    }

    /** @return true if the event was processed now, false if it was a duplicate. */
    @Transactional
    public boolean handle(PaymentEvent event) {
        validate(event);
        int inserted = jdbc.update("""
                INSERT INTO processed_events (consumer, event_id, event_type) VALUES (?, ?, ?)
                ON CONFLICT (consumer, event_id) DO NOTHING
                """, CONSUMER_NAME, event.eventId(), event.eventType());
        if (inserted == 0) {
            log.info("Skipping duplicate event {} ({})", event.eventId(), event.eventType());
            return false;
        }

        BigDecimal amount = new BigDecimal(event.amount());
        String inr = "INR " + amount.toPlainString();
        switch (event.eventType()) {
            case PaymentEvent.PAYMENT_COMPLETED -> {
                notify(event, event.sourceAccountId(), "DEBIT_ALERT", amount, inr + " sent from your account");
                notify(event, event.destinationAccountId(), "CREDIT_ALERT", amount, inr + " received in your account");
            }
            case PaymentEvent.PAYMENT_REFUNDED -> {
                notify(event, event.sourceAccountId(), "REFUND_CREDIT", amount, "Refund of " + inr + " credited to your account");
                notify(event, event.destinationAccountId(), "REFUND_DEBIT", amount, "Refund of " + inr + " debited from your account");
            }
            default -> throw new IllegalArgumentException("Unknown event type " + event.eventType());
        }
        return true;
    }

    private void notify(PaymentEvent event, UUID accountId, String type, BigDecimal amount, String message) {
        notifications.save(new PaymentNotification(event.eventId(), accountId, event.paymentId(), type, amount, message));
    }

    private static void validate(PaymentEvent event) {
        if (event.eventId() == null || event.eventType() == null || event.paymentId() == null
                || event.sourceAccountId() == null || event.destinationAccountId() == null || event.amount() == null) {
            throw new IllegalArgumentException("Payment event is missing required fields");
        }
    }
}
