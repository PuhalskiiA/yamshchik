package ru.yamshchik.yamshchik.adapter.out.persistence;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.yamshchik.yamshchik.application.port.out.DeliveryEventOutbox;
import ru.yamshchik.yamshchik.domain.DeliveryEvent;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;


@Component
@RequiredArgsConstructor
public class JpaDeliveryEventOutbox implements DeliveryEventOutbox {

    private final DeliveryEventEntityRepository eventRepository;

    private final EmailEntityMapper mapper;

    @Override
    @Transactional
    public List<DeliveryEvent> claim(int limit, Instant now, Duration lease) {
        List<DeliveryEventEntity> claimed = eventRepository.findPublishableForUpdate(now, limit);
        claimed.forEach(entity -> entity.setClaimedUntil(now.plus(lease)));
        return claimed.stream().map(mapper::toEvent).toList();
    }

    @Override
    public long countUnpublished() {
        return eventRepository.countByPublishedAtIsNull();
    }

    @Override
    public Optional<Instant> oldestUnpublished() {
        return eventRepository.findOldestUnpublished();
    }

    @Override
    @Transactional
    public void markPublished(List<DeliveryEvent> events, Instant now) {
        eventRepository.markPublished(events.stream().map(DeliveryEvent::id).toList(), now);
    }
}
