package com.fincore.outbox;

import java.time.Instant;

import com.fincore.events.PaymentEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes integration events to the outbox table. MANDATORY propagation: an event can only be
 * recorded inside the business transaction that produced it, so the event exists if and only if
 * the payment/refund committed.
 */
@Service
public class OutboxService {

    private final OutboxRepository outbox;
    private final JsonMapper json;

    public OutboxService(OutboxRepository outbox, JsonMapper json) {
        this.outbox = outbox;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public OutboxEvent record(PaymentEvent event) {
        return outbox.save(new OutboxEvent(event.eventId(), "Payment", event.paymentId(), event.eventType(),
                json.writeValueAsString(event), Instant.now()));
    }
}
