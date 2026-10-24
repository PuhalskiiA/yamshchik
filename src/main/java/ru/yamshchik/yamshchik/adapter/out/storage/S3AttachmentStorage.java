package ru.yamshchik.yamshchik.adapter.out.storage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import ru.yamshchik.yamshchik.application.port.out.AttachmentStorage;
import ru.yamshchik.yamshchik.config.exception.type.AttachmentStorageException;
import ru.yamshchik.yamshchik.config.properties.StorageProperties;
import ru.yamshchik.yamshchik.config.properties.YamshchikProperties;
import ru.yamshchik.yamshchik.domain.Attachment;
import ru.yamshchik.yamshchik.domain.AttachmentContent;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Base64;
import java.util.HexFormat;


@Component
@ConditionalOnProperty(name = StorageProperties.TYPE_PROPERTY, havingValue = StorageProperties.TYPE_S3)
public class S3AttachmentStorage implements AttachmentStorage {

    private static final String MISSING_MESSAGE = "Attachment content is missing: ";

    private static final String UNAVAILABLE_MESSAGE = "Attachment storage is unavailable: ";

    private final S3Client s3Client;

    private final String bucket;

    public S3AttachmentStorage(S3Client s3Client, YamshchikProperties properties) {
        this.s3Client = s3Client;
        this.bucket = properties.getStorage().getS3().getBucket();
    }

    @Override
    public String store(Attachment attachment, AttachmentContent content) {
        // Хранилище само сверяет принятое с контрольной суммой и отклоняет запись при расхождении
        String checksum = Base64.getEncoder().encodeToString(HexFormat.of().parseHex(attachment.sha256()));
        s3Client.putObject(
                request -> request.bucket(bucket)
                        .key(attachment.storageKey())
                        .contentType(attachment.mediaType())
                        .checksumSHA256(checksum),
                // Поставщик, а не готовый поток: при повторе запроса клиент откроет содержимое заново
                RequestBody.fromContentProvider(() -> open(content), attachment.size(), attachment.mediaType()));
        return attachment.storageKey();
    }

    @Override
    public InputStream open(Attachment attachment) {
        try {
            return s3Client.getObject(request -> request.bucket(bucket).key(attachment.storageKey()));
        } catch (NoSuchKeyException e) {
            throw new AttachmentStorageException(MISSING_MESSAGE + attachment.storageKey(), false, e);
        } catch (SdkException e) {
            throw new AttachmentStorageException(UNAVAILABLE_MESSAGE + e.getMessage(), true, e);
        }
    }

    @Override
    public void delete(Attachment attachment) {
        s3Client.deleteObject(request -> request.bucket(bucket).key(attachment.storageKey()));
    }

    private static InputStream open(AttachmentContent content) {
        try {
            return content.open();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
