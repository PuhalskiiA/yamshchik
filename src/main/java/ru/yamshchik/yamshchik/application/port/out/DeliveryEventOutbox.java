package ru.yamshchik.yamshchik.application.port.out;

import ru.yamshchik.yamshchik.domain.DeliveryEvent;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;


/**
 * Неопубликованные события о доставке, записанные вместе с изменением доставки.
 */
public interface DeliveryEventOutbox {

    /**
     * Забирает старейшие неопубликованные события на срок аренды. Пока аренда не истекла, те же события
     * другому вызывающему не достаются; если за это время они не отмечены опубликованными, их заберут снова.
     */
    List<DeliveryEvent> claim(int limit, Instant now, Duration lease);

    void markPublished(List<DeliveryEvent> events, Instant now);

    long countUnpublished();

    /**
     * @return когда произошло самое старое неопубликованное событие
     */
    Optional<Instant> oldestUnpublished();
}
