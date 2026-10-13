package com.fincore.fraud;

import java.util.List;

/** Result of synchronous fraud screening, computed before any money moves. */
public record FraudAssessment(int score, FraudDecision decision, List<FraudRule> triggeredRules) {

    public String rulesAsString() {
        return String.join(",", triggeredRules.stream().map(Enum::name).toList());
    }

    public static List<FraudRule> parseRules(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(csv.split(",")).map(FraudRule::valueOf).toList();
    }
}
