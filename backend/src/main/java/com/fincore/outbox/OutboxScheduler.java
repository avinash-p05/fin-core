package com.fincore.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls the outbox. Disabled in tests, which drive {@link OutboxPublisher} directly. */
@Component
@ConditionalOnProperty(prefix = "fincore.outbox", name = "publisher-enabled", havingValue = "true")
class OutboxScheduler {

    private final OutboxPublisher publisher;
    private final OutboxProperties props;

    OutboxScheduler(OutboxPublisher publisher, OutboxProperties props) {
        this.publisher = publisher;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${fincore.outbox.poll-interval}")
    void poll() {
        // Drain full batches back-to-back; stop when a batch comes back short.
        int processed;
        do {
            processed = publisher.publishBatch();
        } while (processed == props.batchSize());
    }
}
