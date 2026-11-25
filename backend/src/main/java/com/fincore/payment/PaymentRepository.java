package com.fincore.payment;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

public interface PaymentRepository extends JpaRepository<Payment, UUID>, JpaSpecificationExecutor<Payment> {

    /** Payments into or out of an account since {@code since}, newest first (fraud copilot history). */
    @Query("""
            select p from Payment p
             where (p.sourceAccountId = :accountId or p.destinationAccountId = :accountId)
               and p.createdAt >= :since
             order by p.createdAt desc, p.id
            """)
    List<Payment> findRecentForAccount(UUID accountId, Instant since, Pageable page);
}
