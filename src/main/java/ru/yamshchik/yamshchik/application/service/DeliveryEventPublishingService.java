package ru.yamshchik.yamshchik.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.yamshchik.yamshchik.application.port.in.PublishDeliveryEventsUseCase;
import ru.yamshchik.yamshchik.application.port.out.DeliveryEventOutbox;
import ru.yamshchik.yamshchik.application.port.out.DeliveryEventPublisher;
import ru.yamshchik.yamshchik.config.properties.EventProperties;
import ru.yamshchik.yamshchik.config.properties.YamshchikProperties;
import ru.yamshchik.yamshchik.domain.DeliveryEvent;

import java.time.Clock;
import java.time.Instant;
import java.util.List;


/**
 * Публикует события из outbox. Если сервис упадёт между публикацией и отметкой, события опубликуются
 * ещё раз по истечении аренды: доставка событий — «хотя бы один раз».
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeliveryEventPublishingService implements PublishDeliveryEventsUseCase {

    private final DeliveryEventOutbox outbox;

    private final DeliveryEventPublisher publisher;

    private final YamshchikProperties properties;

    private final Clock clock;

    @Override
    public int publishNext() {
        EventProperties events = properties.getEvents();
        List<DeliveryEvent> claimed = outbox.claim(events.getBatchSize(), clock.instant(), events.getLease());
        if (claimed.isEmpty()) {
            return 0;
        }
        publisher.publish(claimed);
        Instant now = clock.instant();
        outbox.markPublished(claimed, now);
        log.info("Published {} delivery events", claimed.size());
        return claimed.size();
    }
}
