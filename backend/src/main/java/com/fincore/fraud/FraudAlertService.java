package com.fincore.fraud;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.fincore.common.ApiException;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FraudAlertService {

    private final FraudAlertRepository alerts;

    public FraudAlertService(FraudAlertRepository alerts) {
        this.alerts = alerts;
    }

    /** Raised in the payment transaction, so a held/declined payment always has its alert. */
    @Transactional(propagation = Propagation.MANDATORY)
    public FraudAlert open(UUID paymentId, FraudAssessment assessment) {
        return alerts.save(FraudAlert.open(paymentId, assessment));
    }

    public record AlertFilter(FraudAlertStatus status, FraudDecision decision, UUID paymentId) {
    }

    @Transactional(readOnly = true)
    public Page<FraudAlert> list(AlertFilter filter, Pageable pageable) {
        Specification<FraudAlert> spec = (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (filter.status() != null) {
                p.add(cb.equal(root.get("status"), filter.status()));
            }
            if (filter.decision() != null) {
                p.add(cb.equal(root.get("decision"), filter.decision()));
            }
            if (filter.paymentId() != null) {
                p.add(cb.equal(root.get("paymentId"), filter.paymentId()));
            }
            return cb.and(p.toArray(Predicate[]::new));
        };
        return alerts.findAll(spec, pageable);
    }

    @Transactional(readOnly = true)
    public FraudAlert get(UUID id) {
        return alerts.findById(id).orElseThrow(() -> ApiException.notFound("Fraud alert", id));
    }
}
