package com.fincore.fraud;

public enum FraudAlertStatus {
    OPEN,
    IN_REVIEW,
    /** REVIEW alerts only: the held payment was released and executed. */
    APPROVED,
    /** REVIEW alerts only: the held payment was declined. */
    REJECTED,
    /** DECLINED alerts only: acknowledged by an analyst; the payment stays declined. */
    CLOSED;

    public boolean isTerminal() {
        return this == APPROVED || this == REJECTED || this == CLOSED;
    }
}
