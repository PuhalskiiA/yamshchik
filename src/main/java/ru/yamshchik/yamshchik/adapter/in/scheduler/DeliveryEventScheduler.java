package ru.yamshchik.yamshchik.adapter.in.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.yamshchik.yamshchik.application.port.in.PublishDeliveryEventsUseCase;


/**
 * Периодически публикует события о доставке, пока неопубликованные не кончатся.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DeliveryEventScheduler {

    private final PublishDeliveryEventsUseCase publishDeliveryEventsUseCase;

    @Scheduled(fixedDelayString = "${yamshchik.events.poll-interval}")
    public void publishEvents() {
        try {
            int published;
            do {
                published = publishDeliveryEventsUseCase.publishNext();
            } while (published > 0);
        } catch (RuntimeException e) {
            // События остаются в outbox и уйдут при следующей попытке, когда брокер станет доступен
            log.error("Delivery event publishing failed:", e);
        }
    }
}
