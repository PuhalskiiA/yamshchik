package ru.yamshchik.yamshchik.config.properties;


public enum StorageType {

    /**
     * S3-совместимое объектное хранилище.
     */
    S3,

    /**
     * Каталог на диске экземпляра сервиса.
     */
    FILESYSTEM,

    /**
     * Топик Kafka: вложение — одно сообщение.
     */
    KAFKA
}
