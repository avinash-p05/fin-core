package com.fincore.ledger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.fincore.account.Account;
import com.fincore.account.AccountRepository;
import com.fincore.common.ApiException;
import com.fincore.common.Money;
import com.fincore.ledger.LedgerDtos.AccountMismatch;
import com.fincore.ledger.LedgerDtos.BalanceVerificationResponse;
import com.fincore.ledger.LedgerDtos.LedgerEntryResponse;
import com.fincore.ledger.LedgerDtos.LedgerTransactionResponse;
import com.fincore.ledger.LedgerDtos.LedgerVerificationResponse;
import com.fincore.ledger.LedgerDtos.UnbalancedTransaction;
import com.fincore.ledger.LedgerDtos.VerificationStatus;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side of the ledger: history, filters and verification. Verification results are always
 * computed here from the database; the dashboard only displays them.
 */
@Service
public class LedgerQueryService {

    private final LedgerEntryRepository entries;
    private final LedgerTransactionRepository transactions;
    private final AccountRepository accounts;
    private final JdbcTemplate jdbc;

    public LedgerQueryService(LedgerEntryRepository entries, LedgerTransactionRepository transactions,
                              AccountRepository accounts, JdbcTemplate jdbc) {
        this.entries = entries;
        this.transactions = transactions;
        this.accounts = accounts;
        this.jdbc = jdbc;
    }

    public record EntryFilter(UUID accountId, UUID paymentId, EntryDirection direction,
                              LedgerTransactionType transactionType, Instant from, Instant to) {
    }

    @Transactional(readOnly = true)
    public Page<LedgerEntryResponse> search(EntryFilter filter, Pageable pageable) {
        Specification<LedgerEntry> spec = (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (filter.accountId() != null) {
                p.add(cb.equal(root.get("accountId"), filter.accountId()));
            }
            if (filter.paymentId() != null) {
                p.add(cb.equal(root.get("transaction").get("paymentId"), filter.paymentId()));
            }
            if (filter.direction() != null) {
                p.add(cb.equal(root.get("direction"), filter.direction()));
            }
            if (filter.transactionType() != null) {
                p.add(cb.equal(root.get("transaction").get("type"), filter.transactionType()));
            }
            if (filter.from() != null) {
                p.add(cb.greaterThanOrEqualTo(root.get("createdAt"), filter.from()));
            }
            if (filter.to() != null) {
                p.add(cb.lessThan(root.get("createdAt"), filter.to()));
            }
            return cb.and(p.toArray(Predicate[]::new));
        };
        return entries.findAll(spec, pageable).map(LedgerEntryResponse::from);
    }

    @Transactional(readOnly = true)
    public List<LedgerTransactionResponse> forPayment(UUID paymentId) {
        return transactions.findByPaymentIdOrderByPostedAt(paymentId).stream()
                .map(LedgerQueryService::toResponse)
                .toList();
    }

    /**
     * Reconciles the stored balance against SUM(credits) - SUM(debits). REPEATABLE READ gives both
     * reads the same snapshot, so a concurrent transfer cannot produce a false mismatch.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public BalanceVerificationResponse verifyAccount(UUID accountId) {
        Account account = accounts.findById(accountId).orElseThrow(() -> ApiException.notFound("Account", accountId));
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(amount) FILTER (WHERE direction = 'DEBIT'), 0)  AS debits,
                       COALESCE(SUM(amount) FILTER (WHERE direction = 'CREDIT'), 0) AS credits,
                       COUNT(*) AS entry_count
                  FROM ledger_entries
                 WHERE account_id = ?
                """, (rs, i) -> {
            BigDecimal debits = rs.getBigDecimal("debits").setScale(Money.SCALE);
            BigDecimal credits = rs.getBigDecimal("credits").setScale(Money.SCALE);
            BigDecimal ledgerBalance = credits.subtract(debits);
            BigDecimal difference = account.getBalance().subtract(ledgerBalance);
            return new BalanceVerificationResponse(accountId, account.getBalance(), ledgerBalance, difference,
                    debits, credits, rs.getLong("entry_count"),
                    difference.signum() == 0 ? VerificationStatus.VALID : VerificationStatus.INVALID,
                    Instant.now());
        }, accountId);
    }

    /** Whole-ledger integrity check over one consistent snapshot. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public LedgerVerificationResponse verifyLedger() {
        List<UnbalancedTransaction> unbalanced = jdbc.query("""
                SELECT t.id,
                       COALESCE(SUM(e.amount) FILTER (WHERE e.direction = 'DEBIT'), 0)  AS debits,
                       COALESCE(SUM(e.amount) FILTER (WHERE e.direction = 'CREDIT'), 0) AS credits,
                       COUNT(e.id) AS entry_count
                  FROM ledger_transactions t
                  LEFT JOIN ledger_entries e ON e.ledger_transaction_id = t.id
                 GROUP BY t.id
                HAVING COUNT(e.id) < 2
                    OR COALESCE(SUM(e.amount) FILTER (WHERE e.direction = 'DEBIT'), 0)
                       <> COALESCE(SUM(e.amount) FILTER (WHERE e.direction = 'CREDIT'), 0)
                """, (rs, i) -> new UnbalancedTransaction(rs.getObject("id", UUID.class),
                rs.getBigDecimal("debits"), rs.getBigDecimal("credits"), rs.getLong("entry_count")));

        List<AccountMismatch> mismatches = jdbc.query("""
                SELECT a.id, a.balance,
                       COALESCE(SUM(CASE WHEN e.direction = 'CREDIT' THEN e.amount ELSE -e.amount END), 0) AS ledger_balance
                  FROM accounts a
                  LEFT JOIN ledger_entries e ON e.account_id = a.id
                 GROUP BY a.id, a.balance
                HAVING a.balance <> COALESCE(SUM(CASE WHEN e.direction = 'CREDIT' THEN e.amount ELSE -e.amount END), 0)
                """, (rs, i) -> new AccountMismatch(rs.getObject("id", UUID.class),
                rs.getBigDecimal("balance"), rs.getBigDecimal("ledger_balance")));

        // Completed (or refunded) payments must have exactly one TRANSFER ledger transaction.
        List<UUID> missingLedger = jdbc.query("""
                SELECT p.id
                  FROM payments p
                 WHERE p.status IN ('COMPLETED', 'PARTIALLY_REFUNDED', 'REFUNDED')
                   AND (SELECT COUNT(*) FROM ledger_transactions t
                         WHERE t.payment_id = p.id AND t.transaction_type = 'TRANSFER') <> 1
                """, (rs, i) -> rs.getObject("id", UUID.class));

        record Totals(long transactions, long entries, long accounts, BigDecimal debits, BigDecimal credits) {
        }
        Totals totals = jdbc.queryForObject("""
                SELECT (SELECT COUNT(*) FROM ledger_transactions) AS tx_count,
                       (SELECT COUNT(*) FROM ledger_entries) AS entry_count,
                       (SELECT COUNT(*) FROM accounts) AS account_count,
                       (SELECT COALESCE(SUM(amount), 0) FROM ledger_entries WHERE direction = 'DEBIT') AS debits,
                       (SELECT COALESCE(SUM(amount), 0) FROM ledger_entries WHERE direction = 'CREDIT') AS credits
                """, (rs, i) -> new Totals(rs.getLong("tx_count"), rs.getLong("entry_count"), rs.getLong("account_count"),
                rs.getBigDecimal("debits").setScale(Money.SCALE), rs.getBigDecimal("credits").setScale(Money.SCALE)));

        boolean valid = unbalanced.isEmpty() && mismatches.isEmpty() && missingLedger.isEmpty()
                && totals.debits().compareTo(totals.credits()) == 0;
        return new LedgerVerificationResponse(valid ? VerificationStatus.VALID : VerificationStatus.INVALID,
                totals.transactions(), totals.entries(), totals.accounts(), totals.debits(), totals.credits(),
                unbalanced, mismatches, missingLedger, Instant.now());
    }

    static LedgerTransactionResponse toResponse(LedgerTransaction tx) {
        BigDecimal debits = Money.zero();
        BigDecimal credits = Money.zero();
        for (LedgerEntry e : tx.getEntries()) {
            if (e.getDirection() == EntryDirection.DEBIT) {
                debits = debits.add(e.getAmount());
            } else {
                credits = credits.add(e.getAmount());
            }
        }
        return new LedgerTransactionResponse(tx.getId(), tx.getType(), tx.getPaymentId(), tx.getRefundId(),
                tx.getDescription(), tx.getPostedAt(), debits, credits, debits.compareTo(credits) == 0,
                tx.getEntries().stream().map(LedgerEntryResponse::from).toList());
    }
}
