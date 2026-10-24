package ru.yamshchik.yamshchik.adapter.out.persistence;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import ru.yamshchik.yamshchik.domain.DeliveryStatus;
import ru.yamshchik.yamshchik.domain.Priority;
import ru.yamshchik.yamshchik.domain.ReportAction;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;


public interface DeliveryEntityRepository extends JpaRepository<DeliveryEntity, Long> {

    List<DeliveryEntity> findByEmailIdOrderById(UUID emailId);

    long countByStatus(DeliveryStatus status);

    Optional<DeliveryEntity> findByEmailIdAndRecipient(UUID emailId, String recipient);

    List<DeliveryEntity> findByStatusAndPriorityAndSentAtLessThanEqualAndIncidentAtIsNullOrderBySentAt(
            DeliveryStatus status,
            Priority priority,
            Instant sentBefore,
            Limit limit
    );

    // Ожидаемые статус и попытка в условии отсекают изменение, сделанное параллельно другим обработчиком
    @Modifying
    @Query("""
            UPDATE DeliveryEntity d
            SET d.status = :status, d.attempt = :attempt, d.reportAction = :reportAction,
                d.reportStatus = :reportStatus, d.diagnostic = :diagnostic, d.updatedAt = :updatedAt,
                d.sentAt = :sentAt, d.incidentAt = :incidentAt
            WHERE d.emailId = :emailId AND d.recipient = :recipient
              AND d.status = :expectedStatus AND d.attempt = :expectedAttempt
            """)
    int updateIfUnchanged(
            UUID emailId,
            String recipient,
            DeliveryStatus expectedStatus,
            int expectedAttempt,
            DeliveryStatus status,
            int attempt,
            ReportAction reportAction,
            String reportStatus,
            String diagnostic,
            Instant updatedAt,
            Instant sentAt,
            Instant incidentAt
    );

    // Доставка, ждущая отправки, меняется только вместе с итогом попытки письма, а его уже защищает аренда
    @Modifying
    @Query("""
            UPDATE DeliveryEntity d
            SET d.status = :status, d.attempt = :attempt, d.reportAction = :reportAction,
                d.reportStatus = :reportStatus, d.diagnostic = :diagnostic, d.updatedAt = :updatedAt,
                d.sentAt = :sentAt, d.incidentAt = :incidentAt
            WHERE d.emailId = :emailId AND d.recipient = :recipient AND d.status = :expectedStatus
            """)
    int updateIfStatus(
            UUID emailId,
            String recipient,
            DeliveryStatus expectedStatus,
            DeliveryStatus status,
            int attempt,
            ReportAction reportAction,
            String reportStatus,
            String diagnostic,
            Instant updatedAt,
            Instant sentAt,
            Instant incidentAt
    );

    @Modifying
    @Query("""
            UPDATE DeliveryEntity d
            SET d.incidentAt = :incidentAt, d.updatedAt = :incidentAt
            WHERE d.emailId = :emailId AND d.recipient = :recipient
              AND d.status = :expectedStatus AND d.attempt = :expectedAttempt AND d.incidentAt IS NULL
            """)
    int markIncident(UUID emailId, String recipient, DeliveryStatus expectedStatus, int expectedAttempt,
                     Instant incidentAt);
}
