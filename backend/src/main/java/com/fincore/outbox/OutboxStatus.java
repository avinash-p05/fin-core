package com.fincore.outbox;

public enum OutboxStatus {
    PENDING,
    PUBLISHED,
    /** Gave up after max attempts. Parked for manual inspection/replay; never silently dropped. */
    FAILED
}
