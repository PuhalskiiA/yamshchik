package ru.yamshchik.yamshchik.config.properties;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.net.URI;


@Getter
@Setter
@NoArgsConstructor
@ToString
public class StorageProperties {

    /**
     * Адрес S3-совместимого хранилища.
     */
    @NotNull(message = "Должен быть задан адрес хранилища вложений!")
    private URI endpoint;

    @NotBlank(message = "Должен быть задан регион хранилища вложений!")
    private String region;

    /**
     * Бакет для вложений. Сервис его не создаёт.
     */
    @NotBlank(message = "Должен быть задан бакет хранилища вложений!")
    private String bucket;

    @NotBlank(message = "Должен быть задан ключ доступа к хранилищу вложений!")
    private String accessKey;

    @NotBlank(message = "Должен быть задан секретный ключ хранилища вложений!")
    @ToString.Exclude
    private String secretKey;

    /**
     * Обращение к бакету по пути, а не по поддомену: нужно для MinIO и большинства хранилищ вне AWS.
     */
    private boolean pathStyleAccess;
}
