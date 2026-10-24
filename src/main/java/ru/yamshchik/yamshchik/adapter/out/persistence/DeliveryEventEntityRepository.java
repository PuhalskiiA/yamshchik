package ru.yamshchik.yamshchik.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;


public interface DeliveryEventEntityRepository extends JpaRepository<DeliveryEventEntity, UUID> {

    // SKIP LOCKED: экземпляры сервиса, публикующие одновременно, не ждут друг друга и не берут одни и те же события
    @Query(value = """
            SELECT *
            FROM delivery_event
            WHERE published_at IS NULL AND (claimed_until IS NULL OR claimed_until <= :now)
            ORDER BY occurred_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<DeliveryEventEntity> findPublishableForUpdate(Instant now, int limit);

    long countByPublishedAtIsNull();

    @Query("SELECT MIN(e.occurredAt) FROM DeliveryEventEntity e WHERE e.publishedAt IS NULL")
    Optional<Instant> findOldestUnpublished();

    @Modifying
    @Query("UPDATE DeliveryEventEntity e SET e.publishedAt = :publishedAt WHERE e.id IN :ids")
    void markPublished(Collection<UUID> ids, Instant publishedAt);
}
