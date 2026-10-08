package ru.yamshchik.yamshchik.config.properties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;


@DisplayName("Настройки хранилища вложений")
class StoragePropertiesTest {

    @ParameterizedTest
    @EnumSource(StorageType.class)
    @DisplayName("Выбранное хранилище без своих настроек — ошибка")
    void selectedStorageMustBeConfigured(StorageType type) {
        StorageProperties properties = new StorageProperties();
        properties.setType(type);

        assertThat(properties.isSelectedStorageConfigured())
                .overridingErrorMessage("Хранилище %s выбрано, но его настройки не заданы — старт должен падать", type)
                .isFalse();
    }

    @Test
    @DisplayName("Достаточно настроек только выбранного хранилища")
    void onlySelectedStorageIsRequired() {
        StorageProperties.Filesystem filesystem = new StorageProperties.Filesystem();
        filesystem.setRoot(Path.of("attachments"));
        StorageProperties properties = new StorageProperties();
        properties.setType(StorageType.FILESYSTEM);
        properties.setFilesystem(filesystem);

        assertThat(properties.isSelectedStorageConfigured())
                .overridingErrorMessage("Для каталога на диске настройки S3 и Kafka не нужны")
                .isTrue();
    }

    @Test
    @DisplayName("Невыбранный тип проверяется отдельным ограничением")
    void missingTypeIsNotReportedTwice() {
        assertThat(new StorageProperties().isSelectedStorageConfigured()).isTrue();
    }
}
