package ru.yamshchik.yamshchik.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;


/**
 * Содержимое вложения хранится под одним ключом у всех писем, которые его прикладывали, поэтому запросы ниже работают
 * с ключами: по одной записи на ключ, конечность — по всем письмам с этим ключом.
 */
public interface EmailAttachmentEntityRepository extends JpaRepository<EmailAttachmentEntity, Long> {

    Optional<EmailAttachmentEntity> findFirstBySha256AndDeletedAtIsNullOrderByIdDesc(String sha256);

    @Query(value = """
            SELECT COALESCE(SUM(size_bytes), 0)
            FROM (SELECT DISTINCT ON (storage_key) size_bytes
                  FROM email_attachment
                  WHERE deleted_at IS NULL
                  ORDER BY storage_key) stored
            """, nativeQuery = true)
    long sumStoredBytes();

    // Вложение можно удалить, только если ни одно письмо с ним больше не будет отправляться
    @Query(value = """
            SELECT DISTINCT ON (a.storage_key)
                   a.storage_key AS storageKey, a.filename AS filename, a.media_type AS mediaType,
                   a.disposition AS disposition, a.content_id AS contentId, a.size_bytes AS sizeBytes,
                   a.sha256 AS sha256, a.email_id AS emailId
            FROM email_attachment a
            WHERE a.deleted_at IS NULL
              AND NOT EXISTS (SELECT 1
                              FROM email_attachment other
                              JOIN email e ON e.id = other.email_id
                              WHERE other.storage_key = a.storage_key
                                AND NOT (e.status = 'SENT' AND e.sent_at < :finishedBefore
                                         OR e.status = 'FAILED' AND e.updated_at < :finishedBefore))
            ORDER BY a.storage_key
            LIMIT :limit
            """, nativeQuery = true)
    List<StoredAttachmentView> findFinishedBefore(Instant finishedBefore, int limit);

    @Query(value = """
            SELECT *
            FROM (SELECT DISTINCT ON (a.storage_key)
                         a.storage_key AS storageKey, a.filename AS filename, a.media_type AS mediaType,
                         a.disposition AS disposition, a.content_id AS contentId, a.size_bytes AS sizeBytes,
                         a.sha256 AS sha256, a.email_id AS emailId,
                         (SELECT MAX(COALESCE(e.sent_at, e.updated_at))
                          FROM email_attachment other
                          JOIN email e ON e.id = other.email_id
                          WHERE other.storage_key = a.storage_key) AS finishedAt
                  FROM email_attachment a
                  WHERE a.deleted_at IS NULL
                    AND NOT EXISTS (SELECT 1
                                    FROM email_attachment other
                                    JOIN email e ON e.id = other.email_id
                                    WHERE other.storage_key = a.storage_key
                                      AND e.status NOT IN ('SENT', 'FAILED'))
                  ORDER BY a.storage_key) finished
            ORDER BY finishedAt
            LIMIT :limit
            """, nativeQuery = true)
    List<StoredAttachmentView> findFinishedOldest(int limit);

    @Modifying
    @Query("UPDATE EmailAttachmentEntity a SET a.deletedAt = :deletedAt WHERE a.storageKey = :storageKey")
    void markDeleted(String storageKey, Instant deletedAt);

    /**
     * Одна запись о содержимом вложения.
     */
    interface StoredAttachmentView {

        String getStorageKey();

        String getFilename();

        String getMediaType();

        String getDisposition();

        String getContentId();

        long getSizeBytes();

        String getSha256();

        UUID getEmailId();
    }
}
