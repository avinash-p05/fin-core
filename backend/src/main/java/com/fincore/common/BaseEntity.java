package com.fincore.common;

import java.util.UUID;

import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

/**
 * Entities use application-assigned UUIDs. Implementing {@link Persistable} tells Spring Data that
 * a freshly constructed entity is new, so {@code save()} issues {@code persist} (a plain INSERT)
 * instead of {@code merge} (SELECT then INSERT, and no PERSIST cascade).
 */
@MappedSuperclass
public abstract class BaseEntity implements Persistable<UUID> {

    @Id
    protected UUID id;

    @Transient
    private boolean isNew = true;

    protected BaseEntity() {
    }

    protected BaseEntity(UUID id) {
        this.id = id;
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }
}
