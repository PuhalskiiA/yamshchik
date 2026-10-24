package ru.yamshchik.yamshchik.application.port.out;

import ru.yamshchik.yamshchik.domain.DeliveryEvent;

import java.util.List;


/**
 * Публикация событий о доставке для внешних потребителей.
 */
public interface DeliveryEventPublisher {

    /**
     * Возвращается, только когда все события приняты брокером.
     *
     * @throws RuntimeException если хоть одно событие не опубликовано
     */
    void publish(List<DeliveryEvent> events);
}
