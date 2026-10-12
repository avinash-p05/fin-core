package com.fincore.ledger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import com.fincore.common.BaseEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

/**
 * A posted, balanced group of ledger entries. Immutable: Hibernate never issues UPDATEs for it
 * and a database trigger rejects UPDATE/DELETE.
 */
@Entity
@Immutable
@Table(name = "ledger_transactions")
public class LedgerTransaction extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false)
    private LedgerTransactionType type;

    @Column(name = "payment_id")
    private UUID paymentId;

    @Column(name = "refund_id")
    private UUID refundId;

    private String description;

    @Column(name = "posted_at", nullable = false)
    private Instant postedAt;

    @OneToMany(mappedBy = "transaction", cascade = CascadeType.PERSIST)
    @OrderBy("direction desc, accountId")
    private List<LedgerEntry> entries = new ArrayList<>();

    protected LedgerTransaction() {
    }

    LedgerTransaction(LedgerTransactionType type, UUID paymentId, UUID refundId, String description, Instant postedAt) {
        this.id = UUID.randomUUID();
        this.type = type;
        this.paymentId = paymentId;
        this.refundId = refundId;
        this.description = description;
        this.postedAt = postedAt;
    }

    void addEntry(LedgerEntry entry) {
        entries.add(entry);
    }

    public LedgerTransactionType getType() {
        return type;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public UUID getRefundId() {
        return refundId;
    }

    public String getDescription() {
        return description;
    }

    public Instant getPostedAt() {
        return postedAt;
    }

    public List<LedgerEntry> getEntries() {
        return Collections.unmodifiableList(entries);
    }
}
