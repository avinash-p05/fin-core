package com.fincore.fraud;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Synchronous, rule-based fraud screening. Scores are additive; the total maps to a decision via
 * the configured thresholds. Runs before any balance changes, inside the payment transaction.
 */
@Service
public class FraudService {

    private static final Logger log = LoggerFactory.getLogger(FraudService.class);

    private final FraudProperties props;
    private final VelocityTracker velocity;
    private final JdbcTemplate jdbc;

    public FraudService(FraudProperties props, VelocityTracker velocity, JdbcTemplate jdbc) {
        this.props = props;
        this.velocity = velocity;
        this.jdbc = jdbc;
    }

    public FraudAssessment evaluate(UUID sourceAccountId, UUID destinationAccountId, BigDecimal amount) {
        int score = 0;
        List<FraudRule> rules = new ArrayList<>();
        FraudDecision floor = FraudDecision.APPROVED;

        if (props.highValue().enabled() && amount.compareTo(props.highValue().threshold()) >= 0) {
            score += props.highValue().score();
            rules.add(FraudRule.HIGH_VALUE);
        }

        if (props.newBeneficiary().enabled() && !hasSettledPaymentBetween(sourceAccountId, destinationAccountId)) {
            score += props.newBeneficiary().score();
            rules.add(FraudRule.NEW_BENEFICIARY);
        }

        if (props.velocity().enabled()) {
            try {
                long count = velocity.recordAndCount(sourceAccountId, props.velocity().window());
                if (count > props.velocity().maxTransactions()) {
                    score += props.velocity().score();
                    rules.add(FraudRule.VELOCITY);
                }
            } catch (VelocityUnavailableException e) {
                // Fail closed: a mandatory control we cannot evaluate must not approve the payment.
                log.warn("Velocity rule unavailable for account {}; applying {} policy", sourceAccountId,
                        props.velocity().unavailableDecision(), e);
                rules.add(FraudRule.VELOCITY_CHECK_UNAVAILABLE);
                floor = props.velocity().unavailableDecision();
            }
        }

        FraudDecision byScore = score >= props.declineThreshold() ? FraudDecision.DECLINED
                : score >= props.reviewThreshold() ? FraudDecision.REVIEW
                : FraudDecision.APPROVED;
        return new FraudAssessment(score, byScore.atLeast(floor), List.copyOf(rules));
    }

    private boolean hasSettledPaymentBetween(UUID source, UUID destination) {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM payments
                                WHERE source_account_id = ? AND destination_account_id = ?
                                  AND status IN ('COMPLETED', 'PARTIALLY_REFUNDED', 'REFUNDED'))
                """, Boolean.class, source, destination);
        return Boolean.TRUE.equals(exists);
    }
}
