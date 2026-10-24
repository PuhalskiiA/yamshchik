package ru.yamshchik.yamshchik.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import ru.yamshchik.yamshchik.domain.EmailStatus;
import ru.yamshchik.yamshchik.domain.TrafficClass;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;


public interface EmailEntityRepository extends JpaRepository<EmailEntity, UUID> {

    Optional<EmailStateView> findStateById(UUID id);

    long countByStatus(EmailStatus status);

    long countByTrafficClassAndStatusIn(TrafficClass trafficClass, Collection<EmailStatus> statuses);

    // У писем в конечном статусе available_at пуст, поэтому статус в условии не нужен.
    // SKIP LOCKED: параллельные обработчики не ждут друг друга и не получают одно и то же письмо
    @Query(value = """
            SELECT *
            FROM email
            WHERE available_at <= :now AND traffic_class = :trafficClass
            ORDER BY available_at
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<EmailEntity> findNextAvailableForUpdate(Instant now, String trafficClass);

    @Modifying
    @Query("""
            UPDATE EmailEntity e
            SET e.availableAt = :leaseUntil
            WHERE e.id = :id AND e.status = :status AND e.attempts = :attempts
            """)
    int extendLease(UUID id, EmailStatus status, int attempts, Instant leaseUntil);

    @Modifying
    @Query("""
            UPDATE EmailEntity e
            SET e.status = :status, e.attempts = :attempts, e.lastError = :lastError,
                e.updatedAt = :updatedAt, e.sentAt = :sentAt, e.availableAt = :availableAt
            WHERE e.id = :id
            """)
    void updateState(
            UUID id,
            EmailStatus status,
            int attempts,
            String lastError,
            Instant updatedAt,
            Instant sentAt,
            Instant availableAt
    );

    @Modifying
    @Query("""
            UPDATE EmailEntity e
            SET e.status = :status, e.attempts = :attempts, e.updatedAt = :updatedAt, e.availableAt = :availableAt
            WHERE e.id = :id AND e.status = :expectedStatus AND e.attempts = :expectedAttempts
            """)
    int postpone(UUID id, EmailStatus expectedStatus, int expectedAttempts, EmailStatus status, int attempts,
                 Instant updatedAt, Instant availableAt);

    // Номер попытки в условии отсекает итог обработчика, у которого письмо уже забрали по истечении аренды
    @Modifying
    @Query("""
            UPDATE EmailEntity e
            SET e.status = :status, e.lastError = :lastError,
                e.updatedAt = :updatedAt, e.sentAt = :sentAt, e.availableAt = :availableAt
            WHERE e.id = :id AND e.status = :expectedStatus AND e.attempts = :attempts
            """)
    int updateStateOfAttempt(
            UUID id,
            EmailStatus expectedStatus,
            int attempts,
            EmailStatus status,
            String lastError,
            Instant updatedAt,
            Instant sentAt,
            Instant availableAt
    );

    /**
     * Состояние письма без содержимого и вложений.
     */
    interface EmailStateView {

        UUID getId();

        EmailStatus getStatus();

        int getAttempts();

        String getLastError();

        Instant getCreatedAt();

        Instant getUpdatedAt();

        Instant getSentAt();

        Instant getAvailableAt();
    }
}
