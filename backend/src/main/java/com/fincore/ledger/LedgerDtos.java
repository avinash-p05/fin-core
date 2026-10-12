package com.fincore.ledger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class LedgerDtos {

    private LedgerDtos() {
    }

    public record LedgerEntryResponse(
            UUID id,
            UUID ledgerTransactionId,
            LedgerTransactionType transactionType,
            UUID paymentId,
            UUID refundId,
            UUID accountId,
            EntryDirection direction,
            BigDecimal amount,
            String currency,
            Instant postedAt) {

        public static LedgerEntryResponse from(LedgerEntry e) {
            LedgerTransaction tx = e.getTransaction();
            return new LedgerEntryResponse(e.getId(), tx.getId(), tx.getType(), tx.getPaymentId(), tx.getRefundId(),
                    e.getAccountId(), e.getDirection(), e.getAmount(), e.getCurrency(), e.getCreatedAt());
        }
    }

    public record LedgerTransactionResponse(
            UUID id,
            LedgerTransactionType type,
            UUID paymentId,
            UUID refundId,
            String description,
            Instant postedAt,
            BigDecimal totalDebits,
            BigDecimal totalCredits,
            boolean balanced,
            List<LedgerEntryResponse> entries) {
    }

    public enum VerificationStatus {
        VALID,
        INVALID
    }

    /** Stored balance vs. balance recomputed from ledger history. */
    public record BalanceVerificationResponse(
            UUID accountId,
            BigDecimal storedBalance,
            BigDecimal ledgerBalance,
            BigDecimal difference,
            BigDecimal totalDebits,
            BigDecimal totalCredits,
            long entryCount,
            VerificationStatus status,
            Instant verifiedAt) {
    }

    public record UnbalancedTransaction(UUID ledgerTransactionId, BigDecimal debits, BigDecimal credits, long entryCount) {
    }

    public record AccountMismatch(UUID accountId, BigDecimal storedBalance, BigDecimal ledgerBalance) {
    }

    /** Whole-ledger integrity check computed from one consistent database snapshot. */
    public record LedgerVerificationResponse(
            VerificationStatus status,
            long transactionsChecked,
            long entriesChecked,
            long accountsChecked,
            BigDecimal totalDebits,
            BigDecimal totalCredits,
            List<UnbalancedTransaction> unbalancedTransactions,
            List<AccountMismatch> accountMismatches,
            List<UUID> completedPaymentsWithoutLedger,
            Instant verifiedAt) {
    }
}
