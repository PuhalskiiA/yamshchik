package ru.yamshchik.yamshchik.adapter.out.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.yamshchik.yamshchik.TestData;
import ru.yamshchik.yamshchik.config.exception.type.AttachmentStorageException;
import ru.yamshchik.yamshchik.config.exception.type.ServiceException;
import ru.yamshchik.yamshchik.config.properties.StorageProperties;
import ru.yamshchik.yamshchik.config.properties.YamshchikProperties;
import ru.yamshchik.yamshchik.domain.Attachment;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


@DisplayName("Хранение вложений в каталоге")
class FilesystemAttachmentStorageTest {

    private static final byte[] CONTENT = "содержимое".getBytes(StandardCharsets.UTF_8);

    @TempDir
    private Path root;

    private FilesystemAttachmentStorage storage;

    @BeforeEach
    void setUp() {
        StorageProperties.Filesystem filesystem = new StorageProperties.Filesystem();
        filesystem.setRoot(root);
        StorageProperties storageProperties = new StorageProperties();
        storageProperties.setFilesystem(filesystem);
        YamshchikProperties properties = new YamshchikProperties();
        properties.setStorage(storageProperties);
        storage = new FilesystemAttachmentStorage(properties);
    }

    @Test
    @DisplayName("Сохранённое содержимое читается обратно без изменений")
    void storeAndOpen() throws IOException {
        Attachment attachment = TestData.attachment("a.txt");

        String key = storage.store(attachment, () -> new ByteArrayInputStream(CONTENT));

        assertThat(key)
                .overridingErrorMessage("Каталог не назначает своё место: ключ должен остаться предложенным")
                .isEqualTo(attachment.storageKey());
        try (InputStream input = storage.open(attachment)) {
            assertThat(input.readAllBytes())
                    .overridingErrorMessage("Прочитанное содержимое отличается от сохранённого")
                    .isEqualTo(CONTENT);
        }
    }

    @Test
    @DisplayName("После записи не остаётся временных файлов")
    void noTemporaryFilesLeft() throws IOException {
        storage.store(TestData.attachment("a.txt"), () -> new ByteArrayInputStream(CONTENT));

        try (Stream<Path> files = Files.walk(root)) {
            assertThat(files.filter(Files::isRegularFile).map(path -> path.getFileName().toString()))
                    .overridingErrorMessage("В каталоге должен лежать только сам файл вложения")
                    .containsExactly("a.txt");
        }
    }

    @Test
    @DisplayName("Отсутствующий файл — постоянный отказ")
    void missingFileIsPermanentFailure() {
        assertThatThrownBy(() -> storage.open(TestData.attachment("missing.txt")))
                .isInstanceOfSatisfying(AttachmentStorageException.class, e ->
                        assertThat(e.isTemporary())
                                .overridingErrorMessage("Пропавший файл не появится при повторной попытке")
                                .isFalse());
    }

    @Test
    @DisplayName("Удалённое вложение больше не читается, повторное удаление не падает")
    void delete() {
        Attachment attachment = TestData.attachment("a.txt");
        storage.store(attachment, () -> new ByteArrayInputStream(CONTENT));

        storage.delete(attachment);

        assertThatThrownBy(() -> storage.open(attachment)).isInstanceOf(AttachmentStorageException.class);
        assertThatCode(() -> storage.delete(attachment))
                .overridingErrorMessage("Удаление уже удалённого вложения не должно быть ошибкой")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Ключ, выводящий за пределы каталога, отклоняется")
    void keyOutsideOfRootIsRejected() {
        Attachment outside = TestData.attachment("a.txt").withStorageKey("../../outside.txt");

        assertThatThrownBy(() -> storage.store(outside, () -> new ByteArrayInputStream(CONTENT)))
                .overridingErrorMessage("Запись за пределы каталога вложений должна быть запрещена")
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> storage.open(outside)).isInstanceOf(ServiceException.class);
    }

    @Test
    @DisplayName("Сбой чтения источника не оставляет файла под именем вложения")
    void failedWriteLeavesNoFile() {
        Attachment attachment = TestData.attachment("a.txt");

        assertThatThrownBy(() -> storage.store(attachment, () -> {
            throw new IOException("upload aborted");
        })).isInstanceOf(ServiceException.class);

        assertThat(root.resolve(attachment.storageKey()))
                .overridingErrorMessage("Недописанное вложение не должно быть видно под своим именем")
                .doesNotExist();
    }
}
