package ru.yamshchik.yamshchik.adapter.out.storage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import ru.yamshchik.yamshchik.application.port.out.AttachmentStorage;
import ru.yamshchik.yamshchik.config.exception.type.AttachmentStorageException;
import ru.yamshchik.yamshchik.config.exception.type.ServiceException;
import ru.yamshchik.yamshchik.config.properties.StorageProperties;
import ru.yamshchik.yamshchik.config.properties.YamshchikProperties;
import ru.yamshchik.yamshchik.domain.Attachment;
import ru.yamshchik.yamshchik.domain.AttachmentContent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;


/**
 * Вложения в каталоге на диске — исходный вариант для сравнения. Каталог принадлежит одному экземпляру
 * сервиса: другой экземпляр вложение не увидит, а с потерей диска оно пропадает.
 */
@Component
@ConditionalOnProperty(name = StorageProperties.TYPE_PROPERTY, havingValue = StorageProperties.TYPE_FILESYSTEM)
public class FilesystemAttachmentStorage implements AttachmentStorage {

    private static final String TEMP_SUFFIX = ".part";

    private static final String WRITE_ERROR_MESSAGE = "Failed to write attachment content: ";

    private static final String MISSING_MESSAGE = "Attachment content is missing: ";

    private static final String UNAVAILABLE_MESSAGE = "Attachment content is unreadable: ";

    private final Path root;

    public FilesystemAttachmentStorage(YamshchikProperties properties) {
        this.root = properties.getStorage().getFilesystem().getRoot().toAbsolutePath().normalize();
    }

    @Override
    public String store(Attachment attachment, AttachmentContent content) {
        Path target = resolve(attachment.storageKey());
        Path temp = target.resolveSibling(target.getFileName() + TEMP_SUFFIX);
        try (InputStream input = content.open()) {
            Files.createDirectories(target.getParent());
            // Сначала во временный файл: недописанное вложение не должно быть видно под своим именем
            Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING);
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return attachment.storageKey();
        } catch (IOException e) {
            throw new ServiceException(WRITE_ERROR_MESSAGE + attachment.storageKey(), e);
        }
    }

    @Override
    public InputStream open(Attachment attachment) {
        try {
            return Files.newInputStream(resolve(attachment.storageKey()));
        } catch (NoSuchFileException e) {
            throw new AttachmentStorageException(MISSING_MESSAGE + attachment.storageKey(), false, e);
        } catch (IOException e) {
            throw new AttachmentStorageException(UNAVAILABLE_MESSAGE + attachment.storageKey(), true, e);
        }
    }

    @Override
    public void delete(Attachment attachment) {
        try {
            Files.deleteIfExists(resolve(attachment.storageKey()));
        } catch (IOException e) {
            throw new ServiceException("Failed to delete attachment content: " + attachment.storageKey(), e);
        }
    }

    private Path resolve(String storageKey) {
        Path path = root.resolve(storageKey).normalize();
        if (!path.startsWith(root)) {
            throw new ServiceException("Attachment key points outside of the storage directory: " + storageKey);
        }
        return path;
    }
}
