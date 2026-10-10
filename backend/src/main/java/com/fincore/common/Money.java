package com.fincore.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * INR money helpers. Amounts are always BigDecimal with scale 2 (paise).
 */
public final class Money {

    public static final String CURRENCY = "INR";
    public static final int SCALE = 2;

    private Money() {
    }

    /**
     * Normalises a client-supplied amount to scale 2. Rejects values with more than two decimal
     * places instead of silently rounding them.
     */
    public static BigDecimal normalize(BigDecimal amount) {
        if (amount == null) {
            throw ApiException.badRequest("INVALID_AMOUNT", "Amount is required");
        }
        try {
            return amount.setScale(SCALE, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw ApiException.badRequest("INVALID_AMOUNT", "Amount must have at most two decimal places");
        }
    }

    public static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(SCALE);
    }
}
