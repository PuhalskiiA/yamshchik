package ru.yamshchik.yamshchik.application.port.in;

/**
 * Публикация событий о доставке.
 */
public interface PublishDeliveryEventsUseCase {

    /**
     * Публикует одну пачку неопубликованных событий.
     *
     * @return сколько событий опубликовано; 0 — публиковать нечего
     */
    int publishNext();
}
