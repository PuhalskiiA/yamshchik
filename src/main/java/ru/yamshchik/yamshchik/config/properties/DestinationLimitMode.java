package ru.yamshchik.yamshchik.config.properties;


public enum DestinationLimitMode {

    /**
     * Темп на сервер получателя не ограничивается.
     */
    NONE,

    /**
     * Темп на каждый сервер получателя хранится в Redis и общий для всех экземпляров.
     */
    REDIS
}
