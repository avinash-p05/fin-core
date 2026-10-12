package com.fincore.ledger;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerTransactionRepository extends JpaRepository<LedgerTransaction, UUID> {

    @EntityGraph(attributePaths = "entries")
    List<LedgerTransaction> findByPaymentIdOrderByPostedAt(UUID paymentId);
}
