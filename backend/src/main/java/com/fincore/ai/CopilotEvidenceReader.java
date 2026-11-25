package com.fincore.ai;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fincore.account.Account;
import com.fincore.account.AccountService;
import com.fincore.ai.CopilotDtos.AccountView;
import com.fincore.ai.CopilotDtos.AlertView;
import com.fincore.ai.CopilotDtos.BeneficiaryHistory;
import com.fincore.ai.CopilotDtos.PaymentView;
import com.fincore.fraud.FraudAlert;
import com.fincore.fraud.FraudAlertService;
import com.fincore.fraud.FraudProperties;
import com.fincore.payment.Payment;
import com.fincore.payment.PaymentRepository;
import com.fincore.payment.PaymentService;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only way the copilot reaches the database. Every method is a read-only transaction that
 * maps entities to views; nothing here (or anything it calls) writes.
 */
@Service
@Transactional(readOnly = true)
public class CopilotEvidenceReader {

    /** Longest customer-supplied description passed to the model. */
    static final int MAX_DESCRIPTION = 140;

    private final FraudAlertService alerts;
    private final PaymentService payments;
    private final PaymentRepository paymentRepository;
    private final AccountService accounts;
    private final FraudProperties fraud;
    private final JdbcTemplate jdbc;

    public CopilotEvidenceReader(FraudAlertService alerts, PaymentService payments, PaymentRepository paymentRepository,
                                 AccountService accounts, FraudProperties fraud, JdbcTemplate jdbc) {
        this.alerts = alerts;
        this.payments = payments;
        this.paymentRepository = paymentRepository;
        this.accounts = accounts;
        this.fraud = fraud;
        this.jdbc = jdbc;
    }

    public AlertView alert(UUID alertId) {
        FraudAlert a = alerts.get(alertId);
        return new AlertView(a.getId(), a.getStatus(), a.getDecision(), a.getRiskScore(), a.getTriggeredRules(),
                a.getCreatedAt(), payment(a.getPaymentId()), fraud.reviewThreshold(), fraud.declineThreshold(),
                fraud.highValue().threshold());
    }

    public PaymentView payment(UUID paymentId) {
        return view(payments.get(paymentId));
    }

    public List<PaymentView> recentPayments(UUID accountId, Instant since, int limit) {
        return paymentRepository.findRecentForAccount(accountId, since, PageRequest.of(0, limit)).stream()
                .map(CopilotEvidenceReader::view)
                .toList();
    }

    public AccountView account(UUID accountId) {
        Account a = accounts.get(accountId);
        long ageDays = Duration.between(a.getCreatedAt(), Instant.now()).toDays();
        return new AccountView(a.getId(), a.getType(), a.getStatus(), a.getCurrency(), a.getBalance(),
                a.getCreatedAt(), ageDays);
    }

    /** Same notion of "known beneficiary" as the NEW_BENEFICIARY rule: settled payments only. */
    public BeneficiaryHistory beneficiaryHistory(UUID source, UUID destination, Payment held) {
        return jdbc.queryForObject("""
                SELECT count(*) AS n, min(created_at) AS first_at, max(created_at) AS last_at,
                       max(amount) AS largest,
                       (percentile_cont(0.5) WITHIN GROUP (ORDER BY amount))::numeric(19,2) AS median
                  FROM payments
                 WHERE source_account_id = ? AND destination_account_id = ? AND id <> ?
                   AND status IN ('COMPLETED', 'PARTIALLY_REFUNDED', 'REFUNDED')
                """, (rs, i) -> {
                    BigDecimal largest = rs.getBigDecimal("largest");
                    BigDecimal ratio = largest == null || largest.signum() == 0 ? null
                            : held.getAmount().divide(largest, 1, RoundingMode.HALF_UP);
                    return new BeneficiaryHistory(source, destination, rs.getLong("n"),
                            instant(rs.getTimestamp("first_at")), instant(rs.getTimestamp("last_at")),
                            largest, rs.getBigDecimal("median"), ratio);
                }, source, destination, held.getId());
    }

    public Payment heldPayment(UUID paymentId) {
        return payments.get(paymentId);
    }

    static PaymentView view(Payment p) {
        String description = p.getDescription();
        if (description != null && description.length() > MAX_DESCRIPTION) {
            description = description.substring(0, MAX_DESCRIPTION);
        }
        return new PaymentView(p.getId(), p.getSourceAccountId(), p.getDestinationAccountId(), p.getAmount(),
                p.getCurrency(), p.getStatus(), p.getRefundedAmount(), p.getFraudScore(), p.getFraudDecision(),
                p.getFraudRules(), description, p.getCreatedAt());
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
