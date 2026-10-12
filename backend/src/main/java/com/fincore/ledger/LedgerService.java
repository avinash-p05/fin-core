package com.fincore.ledger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fincore.account.Account;
import com.fincore.common.Money;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only component that writes ledger rows. It applies the balance change to the (already
 * locked) accounts and inserts the entries in the caller's transaction, so ledger and balances
 * commit or roll back together.
 */
@Service
public class LedgerService {

    private final LedgerTransactionRepository transactions;

    public LedgerService(LedgerTransactionRepository transactions) {
        this.transactions = transactions;
    }

    public record Line(UUID accountId, EntryDirection direction, BigDecimal amount) {
    }

    /** DEBIT {@code from}, CREDIT {@code to}. */
    @Transactional(propagation = Propagation.MANDATORY)
    public LedgerTransaction postTransfer(LedgerTransactionType type, UUID paymentId, UUID refundId, String description,
                                          UUID from, UUID to, BigDecimal amount, Map<UUID, Account> lockedAccounts) {
        return post(type, paymentId, refundId, description, List.of(
                new Line(from, EntryDirection.DEBIT, amount),
                new Line(to, EntryDirection.CREDIT, amount)), lockedAccounts);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public LedgerTransaction post(LedgerTransactionType type, UUID paymentId, UUID refundId, String description,
                                  List<Line> lines, Map<UUID, Account> lockedAccounts) {
        validateBalanced(lines);
        Instant now = Instant.now();
        LedgerTransaction tx = new LedgerTransaction(type, paymentId, refundId, description, now);
        for (Line line : lines) {
            Account account = lockedAccounts.get(line.accountId());
            if (account == null) {
                throw new IllegalStateException("Account " + line.accountId() + " must be locked before posting");
            }
            if (line.direction() == EntryDirection.DEBIT) {
                account.debit(line.amount());
            } else {
                account.credit(line.amount());
            }
            tx.addEntry(new LedgerEntry(tx, line.accountId(), line.direction(), line.amount(), Money.CURRENCY, now));
        }
        return transactions.save(tx);
    }

    /**
     * Application-level guard. The deferred constraint trigger in V2 enforces the same invariant
     * in the database at commit time.
     */
    static void validateBalanced(List<Line> lines) {
        if (lines.size() < 2) {
            throw new IllegalArgumentException("A ledger transaction needs at least two entries");
        }
        BigDecimal debits = BigDecimal.ZERO;
        BigDecimal credits = BigDecimal.ZERO;
        for (Line line : lines) {
            if (line.amount() == null || line.amount().signum() <= 0) {
                throw new IllegalArgumentException("Ledger entry amounts must be positive");
            }
            if (line.direction() == EntryDirection.DEBIT) {
                debits = debits.add(line.amount());
            } else {
                credits = credits.add(line.amount());
            }
        }
        if (debits.compareTo(credits) != 0) {
            throw new IllegalArgumentException("Unbalanced ledger transaction: debits=" + debits + " credits=" + credits);
        }
    }
}
