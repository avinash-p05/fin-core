package com.fincore.common;

import java.util.Map;
import java.util.Optional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pessimistic row locking that is safe with the JPA persistence context.
 *
 * <p>A {@code SELECT ... FOR UPDATE} repository query is not enough on its own: if the entity was
 * already loaded earlier in the same transaction, Hibernate locks the row but returns the cached,
 * possibly stale instance, and the next flush silently overwrites a concurrent update (a lost
 * update). Refreshing the managed instance with {@code PESSIMISTIC_WRITE} re-reads the row while
 * holding the lock.
 */
@Component
public class RowLocks {

    private static final Map<String, Object> LOCK_TIMEOUT_MS = Map.of("jakarta.persistence.lock.timeout", 5000);

    private final EntityManager entityManager;

    public RowLocks(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public <T> Optional<T> lockForUpdate(Class<T> type, Object id) {
        T entity = entityManager.find(type, id);
        if (entity == null) {
            return Optional.empty();
        }
        entityManager.refresh(entity, LockModeType.PESSIMISTIC_WRITE, LOCK_TIMEOUT_MS);
        return Optional.of(entity);
    }
}
