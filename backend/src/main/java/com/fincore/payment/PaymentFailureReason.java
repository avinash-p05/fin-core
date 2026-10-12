package com.fincore.payment;

public enum PaymentFailureReason {
    INSUFFICIENT_FUNDS,
    ACCOUNT_FROZEN,
    FRAUD_DECLINED,
    FRAUD_REVIEW_REJECTED
}
