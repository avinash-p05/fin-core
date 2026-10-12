package com.fincore.payment;

public enum PaymentStatus {
    /** Held by fraud screening. No money has moved and no ledger transaction exists. */
    PENDING_REVIEW,
    /** Balances updated and a balanced TRANSFER ledger transaction posted. */
    COMPLETED,
    PARTIALLY_REFUNDED,
    REFUNDED,
    /** Rejected by fraud screening (automatically or after review). No money moved. */
    DECLINED,
    /** Could not be executed (e.g. insufficient funds, frozen account). No money moved. */
    FAILED;

    public boolean isSettled() {
        return this == COMPLETED || this == PARTIALLY_REFUNDED || this == REFUNDED;
    }
}
