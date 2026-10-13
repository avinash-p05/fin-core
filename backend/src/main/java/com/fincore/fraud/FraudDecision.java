package com.fincore.fraud;

public enum FraudDecision {
    APPROVED,
    REVIEW,
    DECLINED;

    FraudDecision atLeast(FraudDecision other) {
        return this.ordinal() >= other.ordinal() ? this : other;
    }
}
