package com.fincore.dashboard;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.fincore.payment.PaymentDtos.PaymentResponse;
import com.fincore.payment.PaymentService;
import com.fincore.payment.PaymentService.PaymentFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Aggregates for the operations dashboard, computed in PostgreSQL from one snapshot. */
@RestController
@Tag(name = "Dashboard")
public class DashboardController {

    private final JdbcTemplate jdbc;
    private final PaymentService payments;

    public DashboardController(JdbcTemplate jdbc, PaymentService payments) {
        this.jdbc = jdbc;
        this.payments = payments;
    }

    public record DashboardSummary(
            long totalPayments,
            long approvedPayments,
            long underReviewPayments,
            long declinedPayments,
            long failedPayments,
            long settledPayments,
            BigDecimal settledValue,
            BigDecimal refundedValue,
            long openFraudAlerts,
            long pendingOutboxEvents,
            long failedOutboxEvents,
            List<PaymentResponse> recentPayments,
            Instant generatedAt) {
    }

    @GetMapping("/api/v1/dashboard/summary")
    @Operation(summary = "Payment, fraud and outbox counters plus recent payments",
            description = "approvedPayments counts payments whose fraud decision was APPROVED (they may still have "
                    + "FAILED for insufficient funds). settledValue is the gross amount of completed/refunded payments.")
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public DashboardSummary summary() {
        return jdbc.queryForObject("""
                SELECT COUNT(*) AS total,
                       COUNT(*) FILTER (WHERE fraud_decision = 'APPROVED') AS approved,
                       COUNT(*) FILTER (WHERE status = 'PENDING_REVIEW') AS under_review,
                       COUNT(*) FILTER (WHERE status = 'DECLINED') AS declined,
                       COUNT(*) FILTER (WHERE status = 'FAILED') AS failed,
                       COUNT(*) FILTER (WHERE status IN ('COMPLETED', 'PARTIALLY_REFUNDED', 'REFUNDED')) AS settled,
                       COALESCE(SUM(amount) FILTER (WHERE status IN ('COMPLETED', 'PARTIALLY_REFUNDED', 'REFUNDED')), 0) AS settled_value,
                       COALESCE(SUM(refunded_amount), 0) AS refunded_value,
                       (SELECT COUNT(*) FROM fraud_alerts WHERE status IN ('OPEN', 'IN_REVIEW')) AS open_alerts,
                       (SELECT COUNT(*) FROM outbox_events WHERE status = 'PENDING') AS outbox_pending,
                       (SELECT COUNT(*) FROM outbox_events WHERE status = 'FAILED') AS outbox_failed
                  FROM payments
                """, (rs, i) -> new DashboardSummary(
                rs.getLong("total"), rs.getLong("approved"), rs.getLong("under_review"), rs.getLong("declined"),
                rs.getLong("failed"), rs.getLong("settled"), rs.getBigDecimal("settled_value"),
                rs.getBigDecimal("refunded_value"), rs.getLong("open_alerts"), rs.getLong("outbox_pending"),
                rs.getLong("outbox_failed"),
                payments.list(new PaymentFilter(null, null),
                        PageRequest.of(0, 10, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id"))))
                        .map(PaymentResponse::from).getContent(),
                Instant.now()));
    }
}
