package com.fincore.notification;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Notifications")
public class NotificationController {

    private final PaymentNotificationRepository notifications;

    public NotificationController(PaymentNotificationRepository notifications) {
        this.notifications = notifications;
    }

    public record NotificationResponse(UUID id, UUID eventId, UUID paymentId, String type, BigDecimal amount,
                                       String message, Instant createdAt) {
    }

    @GetMapping("/api/v1/accounts/{accountId}/notifications")
    @Operation(summary = "Latest notifications produced by the Kafka consumer for an account")
    @Transactional(readOnly = true)
    public List<NotificationResponse> list(@PathVariable UUID accountId) {
        return notifications.findTop50ByAccountIdOrderByCreatedAtDesc(accountId).stream()
                .map(n -> new NotificationResponse(n.getId(), n.getEventId(), n.getPaymentId(), n.getType(),
                        n.getAmount(), n.getMessage(), n.getCreatedAt()))
                .toList();
    }
}
