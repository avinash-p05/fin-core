package com.fincore.fraud;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** The fraud configuration is validated when the application starts. */
class FraudPropertiesValidationTest {

    @Configuration
    @EnableConfigurationProperties(FraudProperties.class)
    static class Config {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Config.class)
            .withPropertyValues(
                    "fincore.fraud.review-threshold=40",
                    "fincore.fraud.decline-threshold=70",
                    "fincore.fraud.high-value.enabled=true",
                    "fincore.fraud.high-value.threshold=100000.00",
                    "fincore.fraud.high-value.score=50",
                    "fincore.fraud.velocity.enabled=true",
                    "fincore.fraud.velocity.max-transactions=5",
                    "fincore.fraud.velocity.window=60s",
                    "fincore.fraud.velocity.score=40",
                    "fincore.fraud.velocity.unavailable-decision=REVIEW",
                    "fincore.fraud.new-beneficiary.enabled=true",
                    "fincore.fraud.new-beneficiary.score=20");

    @Test
    void validConfigurationStarts() {
        runner.run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(FraudProperties.class));
    }

    @Test
    void approvingWhenRedisIsDownIsRejectedAtStartup() {
        runner.withPropertyValues("fincore.fraud.velocity.unavailable-decision=APPROVED")
                .run(ctx -> assertThat(ctx).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("unavailable-decision must be REVIEW or DECLINED"));
    }

    @Test
    void declineThresholdMustExceedReviewThreshold() {
        runner.withPropertyValues("fincore.fraud.decline-threshold=30")
                .run(ctx -> assertThat(ctx).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("decline-threshold must be greater than review-threshold"));
    }
}
