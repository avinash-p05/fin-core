package com.fincore.ai;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.fincore.ai.CopilotDtos.AccountView;
import com.fincore.ai.CopilotDtos.AlertView;
import com.fincore.ai.CopilotDtos.BeneficiaryHistory;
import com.fincore.ai.CopilotDtos.PaymentView;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * The tools the model may call for ONE alert. A new instance is created per copilot request:
 * <ul>
 *   <li>Read-only: every method goes through {@link CopilotEvidenceReader}.</li>
 *   <li>Scoped: only the alert's source and destination accounts (and payments touching them)
 *       can be read, so text in a payment description cannot steer the model to other customers.</li>
 *   <li>Budgeted: after {@code maxCalls} calls every tool refuses, and the service discards the answer.</li>
 *   <li>Recording: everything returned is kept as {@link Evidence} for the grounding check.</li>
 * </ul>
 * A refused call throws; Spring AI hands the message back to the model as the tool result.
 */
public class AlertScopedTools {

    private final CopilotEvidenceReader reader;
    private final CopilotProperties limits;
    private final AlertView alert;
    private final Set<UUID> scopeAccounts;

    private final Map<UUID, PaymentView> payments = new ConcurrentHashMap<>();
    private final Map<UUID, AccountView> accounts = new ConcurrentHashMap<>();
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicBoolean budgetExceeded = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    public AlertScopedTools(CopilotEvidenceReader reader, CopilotProperties limits, AlertView alert) {
        this.reader = reader;
        this.limits = limits;
        this.alert = alert;
        this.scopeAccounts = Set.of(alert.payment().sourceAccountId(), alert.payment().destinationAccountId());
        payments.put(alert.payment().id(), alert.payment()); // given to the model in the prompt
    }

    @Tool(description = "Get one payment by id. Only payments into or out of the alert's two accounts are visible.")
    public PaymentView getPayment(@ToolParam(description = "Payment id") UUID paymentId) {
        spend();
        PaymentView p = reader.payment(paymentId);
        if (!scopeAccounts.contains(p.sourceAccountId()) && !scopeAccounts.contains(p.destinationAccountId())) {
            throw new IllegalArgumentException("Payment " + paymentId + " is outside this alert's scope");
        }
        payments.put(p.id(), p);
        return p;
    }

    @Tool(description = "Payments into or out of one of the alert's accounts in the last N days, newest first.")
    public List<PaymentView> getRecentPayments(
            @ToolParam(description = "The alert's source or destination account id") UUID accountId,
            @ToolParam(description = "How many days back to look (1-30)") int days) {
        spend();
        requireInScope(accountId);
        int window = Math.clamp(days, 1, limits.maxHistoryDays());
        Instant since = Instant.now().minus(window, ChronoUnit.DAYS);
        List<PaymentView> list = reader.recentPayments(accountId, since, limits.maxHistoryPayments());
        list.forEach(p -> payments.put(p.id(), p));
        return list;
    }

    @Tool(description = "How many settled payments the alert's source account has sent to its destination before.")
    public BeneficiaryHistory getBeneficiaryHistory() {
        spend();
        PaymentView held = alert.payment();
        return reader.beneficiaryHistory(held.sourceAccountId(), held.destinationAccountId(), held.id());
    }

    @Tool(description = "Type, status, balance and age of one of the alert's two accounts.")
    public AccountView getAccountSummary(
            @ToolParam(description = "The alert's source or destination account id") UUID accountId) {
        spend();
        requireInScope(accountId);
        AccountView a = reader.account(accountId);
        accounts.put(a.id(), a);
        return a;
    }

    /** Called when the request ends (normally or by timeout); later tool calls are refused. */
    void close() {
        closed.set(true);
    }

    int callCount() {
        return calls.get();
    }

    boolean budgetExceeded() {
        return budgetExceeded.get();
    }

    Evidence evidence() {
        return new Evidence(alert, scopeAccounts, Map.copyOf(payments), Map.copyOf(accounts));
    }

    private void spend() {
        if (closed.get()) {
            throw new IllegalStateException("This copilot request has ended");
        }
        if (calls.incrementAndGet() > limits.maxToolCalls()) {
            budgetExceeded.set(true);
            throw new IllegalStateException("Tool budget of " + limits.maxToolCalls() + " calls is used up");
        }
    }

    private void requireInScope(UUID accountId) {
        if (!scopeAccounts.contains(accountId)) {
            throw new IllegalArgumentException("Account " + accountId + " is not part of this alert");
        }
    }

    /** Everything the model was shown during one request; the grounding check's source of truth. */
    public record Evidence(AlertView alert, Set<UUID> scopeAccounts, Map<UUID, PaymentView> payments,
                           Map<UUID, AccountView> accounts) {

        /** Every money value the model may legitimately quote. */
        public Set<BigDecimal> knownAmounts() {
            Set<BigDecimal> amounts = new java.util.HashSet<>();
            payments.values().forEach(p -> {
                amounts.add(norm(p.amount()));
                if (p.refundedAmount() != null && p.refundedAmount().signum() > 0) {
                    amounts.add(norm(p.refundedAmount()));
                }
            });
            accounts.values().forEach(a -> amounts.add(norm(a.balance())));
            amounts.add(norm(alert.highValueThreshold()));
            return amounts;
        }

        /** Every id the model may legitimately mention. */
        public Set<UUID> knownIds() {
            Set<UUID> ids = new java.util.HashSet<>(scopeAccounts);
            ids.add(alert.id());
            payments.values().forEach(p -> {
                ids.add(p.id());
                ids.add(p.sourceAccountId());
                ids.add(p.destinationAccountId());
            });
            return ids;
        }

        static BigDecimal norm(BigDecimal v) {
            return v.stripTrailingZeros();
        }
    }
}
