package com.fincore.fraud;

public enum FraudRule {
    HIGH_VALUE,
    VELOCITY,
    NEW_BENEFICIARY,
    /** Redis was unreachable, so velocity could not be evaluated (fail-closed policy applied). */
    VELOCITY_CHECK_UNAVAILABLE
}
