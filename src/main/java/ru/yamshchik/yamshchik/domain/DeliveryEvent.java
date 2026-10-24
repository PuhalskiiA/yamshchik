package ru.yamshchik.yamshchik.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;


/**
 * Событие об окончательном исходе доставки письма одному получателю. Может быть передано больше
 * одного раза: потребитель отбрасывает повторы по {@code id}.
 *
 * @param outcome {@link DeliveryStatus#DELIVERED} или {@link DeliveryStatus#UNDELIVERED}
 * @param action  действие из отчёта о доставке; пусто, если письмо не удалось отправить и отчёта нет
 */
public record DeliveryEvent(
        UUID id,
        EmailId emailId,
        UUID batchId,
        String recipient,
        DeliveryStatus outcome,
        ReportAction action,
        String status,
        String diagnostic,
        Instant occurredAt
) {

    public DeliveryEvent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(emailId, "emailId");
        Objects.requireNonNull(batchId, "batchId");
        Objects.requireNonNull(recipient, "recipient");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }

    public static DeliveryEvent of(Delivery delivery) {
        if (!delivery.isFinal()) {
            throw new IllegalStateException("Delivery %s to %s is not final: %s"
                                                    .formatted(delivery.emailId(), delivery.recipient(), delivery.status()));
        }
        return new DeliveryEvent(UUID.randomUUID(), delivery.emailId(), delivery.batchId(), delivery.recipient(),
                                 delivery.status(), delivery.reportAction(), delivery.reportStatus(),
                                 delivery.diagnostic(), delivery.updatedAt());
    }
}
