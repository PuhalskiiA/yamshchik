package ru.yamshchik.yamshchik.application.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.util.unit.DataSize;
import ru.yamshchik.yamshchik.application.port.out.AttachmentStorage;
import ru.yamshchik.yamshchik.application.port.out.EmailMetrics;
import ru.yamshchik.yamshchik.application.port.out.StoredAttachments;
import ru.yamshchik.yamshchik.config.properties.CleanupProperties;
import ru.yamshchik.yamshchik.config.properties.StorageProperties;
import ru.yamshchik.yamshchik.config.properties.StorageType;
import ru.yamshchik.yamshchik.config.properties.YamshchikProperties;
import ru.yamshchik.yamshchik.domain.Attachment;
import ru.yamshchik.yamshchik.domain.AttachmentDisposition;
import ru.yamshchik.yamshchik.domain.EmailId;
import ru.yamshchik.yamshchik.domain.StoredAttachment;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static ru.yamshchik.yamshchik.TestData.NOW;


@ExtendWith(MockitoExtension.class)
@DisplayName("Уборка хранилища вложений")
class AttachmentCleanupServiceTest {

    private static final long CAPACITY = 1000;

    // Порог 75 % от 1000 байт
    private static final long THRESHOLD = 750;

    private static final Duration MIN_AGE = Duration.ofDays(3);

    private static final int BATCH_SIZE = 10;

    @Mock
    private StoredAttachments storedAttachments;

    @Mock
    private AttachmentStorage attachmentStorage;

    @Mock
    private EmailMetrics metrics;

    private StorageProperties storage;

    private AttachmentCleanupService service;

    @BeforeEach
    void setUp() {
        CleanupProperties cleanup = new CleanupProperties();
        cleanup.setCapacity(DataSize.ofBytes(CAPACITY));
        cleanup.setThresholdPercent(75);
        cleanup.setMinAge(MIN_AGE);
        cleanup.setBatchSize(BATCH_SIZE);
        storage = new StorageProperties();
        storage.setType(StorageType.S3);
        storage.setCleanup(cleanup);
        YamshchikProperties properties = new YamshchikProperties();
        properties.setStorage(storage);
        service = new AttachmentCleanupService(storedAttachments, attachmentStorage, metrics, properties,
                                               Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("Вложения писем, завершённых больше 3 дней назад, удаляются и при свободном хранилище")
    void expiredAreDeletedRegardlessOfFullness() {
        when(storedAttachments.usedBytes()).thenReturn(100L);
        StoredAttachment first = stored("old-1", 50);
        StoredAttachment second = stored("old-2", 50);
        when(storedAttachments.findFinishedBefore(NOW.minus(MIN_AGE), BATCH_SIZE))
                .thenReturn(List.of(first), List.of(second), List.of());

        int deleted = service.cleanUp();

        assertThat(deleted)
                .overridingErrorMessage("Срок хранения истёк — вложения удаляются, даже если места хватает")
                .isEqualTo(2);
        verify(attachmentStorage).delete(first.attachment());
        verify(attachmentStorage).delete(second.attachment());
        verify(storedAttachments).markDeleted("old-1", NOW);
        verify(storedAttachments, never()).findFinishedOldest(anyInt());
        verify(metrics, times(2)).attachmentDeleted(false, 50);
    }

    @Test
    @DisplayName("Места хватает и сроки не вышли — ничего не удаляется")
    void nothingToDelete() {
        when(storedAttachments.usedBytes()).thenReturn(THRESHOLD - 1);
        when(storedAttachments.findFinishedBefore(any(), anyInt())).thenReturn(List.of());

        assertThat(service.cleanUp()).isZero();

        verifyNoInteractions(attachmentStorage);
        verify(storedAttachments, never()).findFinishedOldest(anyInt());
    }

    @Test
    @DisplayName("Не хватает места: удаляются вложения недавно завершённых писем, пока занятое не опустится ниже порога")
    void freedUntilBelowThreshold() {
        when(storedAttachments.usedBytes()).thenReturn(950L);
        when(storedAttachments.findFinishedBefore(any(), anyInt())).thenReturn(List.of());
        when(storedAttachments.findFinishedOldest(BATCH_SIZE))
                .thenReturn(List.of(stored("first", 100), stored("second", 100), stored("third", 100),
                                    stored("fourth", 100)));

        int deleted = service.cleanUp();

        assertThat(deleted)
                .overridingErrorMessage("950 - 100 - 100 = 750 — ещё не ниже порога 750, нужно третье удаление, но не четвёртое")
                .isEqualTo(3);
        verify(attachmentStorage, never()).delete(stored("fourth", 100).attachment());
        verify(metrics, times(3)).attachmentDeleted(eq(true), eq(100L));
    }

    @Test
    @DisplayName("Удалять больше нечего, а места всё равно мало — вложения неотправленных писем остаются")
    void unsentAreNeverDeleted() {
        when(storedAttachments.usedBytes()).thenReturn(900L);
        when(storedAttachments.findFinishedBefore(any(), anyInt())).thenReturn(List.of());
        when(storedAttachments.findFinishedOldest(BATCH_SIZE)).thenReturn(List.of(stored("finished", 100)), List.of());

        int deleted = service.cleanUp();

        assertThat(deleted)
                .overridingErrorMessage("Кроме вложений завершённых писем удалять нельзя ничего, даже при нехватке места")
                .isEqualTo(1);
        verify(attachmentStorage).delete(any());
    }

    @Test
    @DisplayName("Не удалившееся из хранилища вложение не отмечается удалённым и не крутит уборку бесконечно")
    void failedDeletion() {
        when(storedAttachments.usedBytes()).thenReturn(900L);
        when(storedAttachments.findFinishedBefore(any(), anyInt())).thenReturn(List.of());
        StoredAttachment stuck = stored("stuck", 100);
        when(storedAttachments.findFinishedOldest(BATCH_SIZE)).thenReturn(List.of(stuck));
        doThrow(new IllegalStateException("s3 is down")).when(attachmentStorage).delete(stuck.attachment());

        assertThat(service.cleanUp()).isZero();

        verify(storedAttachments, never()).markDeleted(any(), any());
    }

    @Test
    @DisplayName("Из Kafka отдельное вложение не удалить — уборка не запускается")
    void kafkaIsSkipped() {
        storage.setType(StorageType.KAFKA);

        assertThat(service.cleanUp()).isZero();

        verifyNoInteractions(storedAttachments, attachmentStorage);
    }

    private static StoredAttachment stored(String key, long size) {
        Attachment attachment = new Attachment("a.txt", "text/plain", AttachmentDisposition.ATTACHMENT, null,
                                               key, size, "sha");
        return new StoredAttachment(attachment,
                                    new EmailId(UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8))));
    }
}
