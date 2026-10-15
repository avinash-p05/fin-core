package com.fincore.notification;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentNotificationRepository extends JpaRepository<PaymentNotification, UUID> {

    List<PaymentNotification> findTop50ByAccountIdOrderByCreatedAtDesc(UUID accountId);

    long countByEventId(UUID eventId);
}
