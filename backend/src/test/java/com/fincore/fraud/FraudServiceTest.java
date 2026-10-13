package com.fincore.fraud;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** Pure unit tests of scoring and the fail-closed Redis policy (no containers). */
class FraudServiceTest {

    private final UUID source = UUID.randomUUID();
    private final UUID destination = UUID.randomUUID();

    private VelocityTracker velocity;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        velocity = mock(VelocityTracker.class);
        jdbc = mock(JdbcTemplate.class);
        when(velocity.recordAndCount(any(), any())).thenReturn(1L);
        knownBeneficiary(true);
    }

    @Test
    void smallPaymentToKnownBeneficiaryIsApproved() {
        FraudAssessment a = service(FraudDecision.REVIEW).evaluate(source, destination, inr("500"));
        assertThat(a.decision()).isEqualTo(FraudDecision.APPROVED);
        assertThat(a.score()).isZero();
        assertThat(a.triggeredRules()).isEmpty();
    }

    @Test
    void newBeneficiaryAloneStaysBelowReviewThreshold() {
        knownBeneficiary(false);
        FraudAssessment a = service(FraudDecision.REVIEW).evaluate(source, destination, inr("500"));
        assertThat(a.triggeredRules()).containsExactly(FraudRule.NEW_BENEFICIARY);
        assertThat(a.score()).isEqualTo(20);
        assertThat(a.decision()).isEqualTo(FraudDecision.APPROVED);
    }

    @Test
    void highValueToKnownBeneficiaryGoesToReview() {
        FraudAssessment a = service(FraudDecision.REVIEW).evaluate(source, destination, inr("100000.00"));
        assertThat(a.triggeredRules()).containsExactly(FraudRule.HIGH_VALUE);
        assertThat(a.decision()).isEqualTo(FraudDecision.REVIEW);
    }

    @Test
    void highValueToNewBeneficiaryIsDeclined() {
        knownBeneficiary(false);
        FraudAssessment a = service(FraudDecision.REVIEW).evaluate(source, destination, inr("150000.00"));
        assertThat(a.triggeredRules()).containsExactlyInAnyOrder(FraudRule.HIGH_VALUE, FraudRule.NEW_BENEFICIARY);
        assertThat(a.score()).isEqualTo(70);
        assertThat(a.decision()).isEqualTo(FraudDecision.DECLINED);
    }

    @Test
    void velocityAboveLimitTriggersReview() {
        when(velocity.recordAndCount(eq(source), any())).thenReturn(6L);
        FraudAssessment a = service(FraudDecision.REVIEW).evaluate(source, destination, inr("10"));
        assertThat(a.triggeredRules()).containsExactly(FraudRule.VELOCITY);
        assertThat(a.decision()).isEqualTo(FraudDecision.REVIEW);
    }

    @Test
    void velocityAtLimitDoesNotTrigger() {
        when(velocity.recordAndCount(eq(source), any())).thenReturn(5L);
        assertThat(service(FraudDecision.REVIEW).evaluate(source, destination, inr("10")).triggeredRules()).isEmpty();
    }

    @Test
    void redisUnavailableNeverApprovesAndDefaultsToReview() {
        when(velocity.recordAndCount(any(), any())).thenThrow(new VelocityUnavailableException("down", null));
        FraudAssessment a = service(FraudDecision.REVIEW).evaluate(source, destination, inr("10"));
        assertThat(a.decision()).isEqualTo(FraudDecision.REVIEW);
        assertThat(a.triggeredRules()).containsExactly(FraudRule.VELOCITY_CHECK_UNAVAILABLE);
    }

    @Test
    void redisUnavailableCanBeConfiguredToDecline() {
        when(velocity.recordAndCount(any(), any())).thenThrow(new VelocityUnavailableException("down", null));
        assertThat(service(FraudDecision.DECLINED).evaluate(source, destination, inr("10")).decision())
                .isEqualTo(FraudDecision.DECLINED);
    }

    @Test
    void redisUnavailableDoesNotLowerAHigherScoreDecision() {
        knownBeneficiary(false);
        when(velocity.recordAndCount(any(), any())).thenThrow(new VelocityUnavailableException("down", null));
        // 70 points => DECLINED by score, even though the outage policy alone would be REVIEW.
        assertThat(service(FraudDecision.REVIEW).evaluate(source, destination, inr("200000")).decision())
                .isEqualTo(FraudDecision.DECLINED);
    }

    private FraudService service(FraudDecision unavailableDecision) {
        FraudProperties props = new FraudProperties(40, 70,
                new FraudProperties.HighValue(true, new BigDecimal("100000.00"), 50),
                new FraudProperties.Velocity(true, 5, Duration.ofSeconds(60), 40, unavailableDecision),
                new FraudProperties.NewBeneficiary(true, 20));
        return new FraudService(props, velocity, jdbc);
    }

    private void knownBeneficiary(boolean known) {
        when(jdbc.queryForObject(any(String.class), eq(Boolean.class), eq(source), eq(destination))).thenReturn(known);
    }

    private static BigDecimal inr(String v) {
        return new BigDecimal(v).setScale(2);
    }
}
