package ru.yamshchik.yamshchik.domain;

public enum EmailStatus {

    /**
     * Принято и ждёт отправки.
     */
    ACCEPTED,

    /**
     * Взято в отправку.
     */
    SENDING,

    /**
     * Попытка не удалась, письмо ждёт следующей.
     */
    DEFERRED,

    /**
     * Почтовый сервер принял письмо.
     */
    SENT,

    /**
     * Отправить не удалось.
     */
    FAILED
}
