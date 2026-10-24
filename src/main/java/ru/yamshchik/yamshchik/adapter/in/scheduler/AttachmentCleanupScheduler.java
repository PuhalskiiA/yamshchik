package ru.yamshchik.yamshchik.adapter.in.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.yamshchik.yamshchik.application.port.in.CleanUpAttachmentsUseCase;


/**
 * Периодически проверяет заполненность хранилища вложений и при необходимости освобождает его.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AttachmentCleanupScheduler {

    private final CleanUpAttachmentsUseCase cleanUpAttachmentsUseCase;

    @Scheduled(fixedDelayString = "${yamshchik.storage.cleanup.interval}")
    public void cleanUp() {
        try {
            cleanUpAttachmentsUseCase.cleanUp();
        } catch (RuntimeException e) {
            log.error("Attachment storage cleanup failed:", e);
        }
    }
}
