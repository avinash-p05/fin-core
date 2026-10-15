package com.fincore.outbox;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Claims due events. {@code SKIP LOCKED} lets several publisher instances run concurrently
     * without blocking on (or double-publishing) each other's rows.
     */
    @Query(value = """
            SELECT * FROM outbox_events
             WHERE status = 'PENDING' AND next_attempt_at <= :now
             ORDER BY created_at
             LIMIT :limit
             FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> lockDueEvents(@Param("now") Instant now, @Param("limit") int limit);

    List<OutboxEvent> findByAggregateIdOrderByCreatedAt(UUID aggregateId);
}
