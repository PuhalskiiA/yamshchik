package ru.yamshchik.yamshchik.config.properties;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.springframework.util.unit.DataSize;

import java.net.URI;
import java.nio.file.Path;


@Getter
@Setter
@NoArgsConstructor
@ToString
public class StorageProperties {

    public static final String TYPE_PROPERTY = "yamshchik.storage.type";

    public static final String TYPE_S3 = "s3";

    public static final String TYPE_FILESYSTEM = "filesystem";

    public static final String TYPE_KAFKA = "kafka";

    /**
     * Где хранится содержимое вложений.
     */
    @NotNull(message = "Должно быть выбрано хранилище вложений!")
    private StorageType type;

    @Valid
    private S3 s3;

    @Valid
    private Filesystem filesystem;

    @Valid
    private Kafka kafka;

    @AssertTrue(message = "Для выбранного хранилища вложений должны быть заданы его настройки!")
    public boolean isSelectedStorageConfigured() {
        if (type == null) {
            return true;
        }
        return switch (type) {
            case S3 -> s3 != null;
            case FILESYSTEM -> filesystem != null;
            case KAFKA -> kafka != null;
        };
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @ToString
    public static class S3 {

        /**
         * Адрес S3-совместимого хранилища.
         */
        @NotNull(message = "Должен быть задан адрес хранилища S3!")
        private URI endpoint;

        @NotBlank(message = "Должен быть задан регион хранилища S3!")
        private String region;

        /**
         * Бакет для вложений. Сервис его не создаёт.
         */
        @NotBlank(message = "Должен быть задан бакет хранилища S3!")
        private String bucket;

        @NotBlank(message = "Должен быть задан ключ доступа к хранилищу S3!")
        private String accessKey;

        @NotBlank(message = "Должен быть задан секретный ключ хранилища S3!")
        @ToString.Exclude
        private String secretKey;

        /**
         * Обращение к бакету по пути, а не по поддомену: нужно для MinIO и большинства хранилищ вне AWS.
         */
        private boolean pathStyleAccess;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @ToString
    public static class Filesystem {

        /**
         * Каталог для вложений. Виден только тому экземпляру сервиса, на диске которого лежит.
         */
        @NotNull(message = "Должен быть задан каталог для вложений!")
        private Path root;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @ToString
    public static class Kafka {

        @NotBlank(message = "Должны быть заданы адреса брокеров Kafka для вложений!")
        private String bootstrapServers;

        /**
         * Топик для вложений. Наибольший размер сообщения в нём должен быть не меньше {@link #maxRecordSize}.
         */
        @NotBlank(message = "Должен быть задан топик Kafka для вложений!")
        private String topic;

        /**
         * Наибольший размер одного вложения: оно целиком кладётся в одно сообщение.
         */
        @NotNull(message = "Должен быть задан наибольший размер сообщения Kafka с вложением!")
        private DataSize maxRecordSize;
    }
}
