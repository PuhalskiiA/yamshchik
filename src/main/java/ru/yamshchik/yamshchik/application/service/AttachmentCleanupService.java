package ru.yamshchik.yamshchik.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.yamshchik.yamshchik.application.port.in.CleanUpAttachmentsUseCase;
import ru.yamshchik.yamshchik.application.port.out.AttachmentStorage;
import ru.yamshchik.yamshchik.application.port.out.EmailMetrics;
import ru.yamshchik.yamshchik.application.port.out.StoredAttachments;
import ru.yamshchik.yamshchik.config.properties.CleanupProperties;
import ru.yamshchik.yamshchik.config.properties.StorageType;
import ru.yamshchik.yamshchik.config.properties.YamshchikProperties;
import ru.yamshchik.yamshchik.domain.StoredAttachment;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;


/**
 * Удаляет содержимое вложений из хранилища. Удаляется только то, что больше не понадобится для отправки:
 * все письма с этим вложением отправлены или окончательно не отправлены. Вложения писем, которые ещё ждут
 * отправки, не удаляются никогда — даже если места не хватает.
 * <ul>
 *   <li>По сроку: вложения писем, завершённых раньше срока хранения, удаляются при каждом запуске.</li>
 *   <li>По заполненности: если хранилище всё равно заполнено выше порога, удаляются вложения недавно
 *       завершённых писем, от давних к свежим, пока заполненность не опустится ниже порога.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AttachmentCleanupService implements CleanUpAttachmentsUseCase {

    private static final int PERCENT = 100;

    private final StoredAttachments storedAttachments;

    private final AttachmentStorage attachmentStorage;

    private final EmailMetrics metrics;

    private final YamshchikProperties properties;

    private final Clock clock;

    @Override
    public int cleanUp() {
        // Из топика Kafka отдельное сообщение не удалить: место освобождает срок хранения топика
        if (properties.getStorage().getType() == StorageType.KAFKA) {
            return 0;
        }
        CleanupProperties cleanup = properties.getStorage().getCleanup();
        Instant finishedBefore = clock.instant().minus(cleanup.getMinAge());
        Cleanup expired = deleteWhile(() -> storedAttachments.findFinishedBefore(finishedBefore, cleanup.getBatchSize()),
                                      storedAttachments.usedBytes(), Long.MIN_VALUE, false);
        if (expired.deleted() > 0) {
            log.info("Attachment storage cleanup deleted {} attachments of emails finished before {}",
                     expired.deleted(), finishedBefore);
        }
        if (expired.used() < cleanup.thresholdBytes()) {
            return expired.deleted();
        }

        log.warn("Attachment storage is {}% full ({} of {} bytes), deleting attachments of recently finished emails",
                 percent(expired.used(), cleanup), expired.used(), cleanup.getCapacity().toBytes());
        Cleanup freed = deleteWhile(() -> storedAttachments.findFinishedOldest(cleanup.getBatchSize()),
                                    expired.used(), cleanup.thresholdBytes(), true);
        if (freed.used() >= cleanup.thresholdBytes()) {
            log.error("Attachment storage is still {}% full ({} bytes): the rest belongs to emails that are not sent "
                              + "yet and is kept", percent(freed.used(), cleanup), freed.used());
        } else {
            log.warn("Attachment storage cleanup deleted {} attachments of recently finished emails, {} bytes are used",
                     freed.deleted(), freed.used());
        }
        return expired.deleted() + freed.deleted();
    }

    private static long percent(long used, CleanupProperties cleanup) {
        return used * PERCENT / cleanup.getCapacity().toBytes();
    }

    /**
     * Удаляет пачками, пока занятое не опустится ниже {@code stopBelow} или удалять станет нечего.
     * Пачка, из которой ничего удалить не удалось, останавливает удаление: она вернулась бы снова.
     */
    private Cleanup deleteWhile(Supplier<List<StoredAttachment>> batches, long used, long stopBelow, boolean forced) {
        int deleted = 0;
        long remaining = used;
        while (remaining >= stopBelow) {
            int deletedInBatch = 0;
            for (StoredAttachment stored : batches.get()) {
                if (remaining < stopBelow) {
                    break;
                }
                if (delete(stored, forced)) {
                    remaining -= stored.attachment().size();
                    deletedInBatch++;
                }
            }
            if (deletedInBatch == 0) {
                break;
            }
            deleted += deletedInBatch;
        }
        return new Cleanup(deleted, remaining);
    }

    private boolean delete(StoredAttachment stored, boolean forced) {
        String key = stored.attachment().storageKey();
        try {
            attachmentStorage.delete(stored.attachment());
        } catch (RuntimeException e) {
            log.warn("Attachment {} was not deleted from storage", key, e);
            return false;
        }
        storedAttachments.markDeleted(key, clock.instant());
        metrics.attachmentDeleted(forced, stored.attachment().size());
        if (forced) {
            log.warn("Attachment {} of recently finished email {} is deleted to free storage", key, stored.emailId());
        } else {
            log.info("Attachment {} of email {} is deleted after retention period", key, stored.emailId());
        }
        return true;
    }

    private record Cleanup(int deleted, long used) {
    }
}
