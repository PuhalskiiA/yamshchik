package ru.yamshchik.yamshchik.application.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.yamshchik.yamshchik.TestData;
import ru.yamshchik.yamshchik.application.port.out.DeliveryEventOutbox;
import ru.yamshchik.yamshchik.application.port.out.DeliveryEventPublisher;
import ru.yamshchik.yamshchik.config.exception.type.ServiceException;
import ru.yamshchik.yamshchik.config.properties.EventProperties;
import ru.yamshchik.yamshchik.config.properties.YamshchikProperties;
import ru.yamshchik.yamshchik.domain.DeliveryEvent;
import ru.yamshchik.yamshchik.domain.EmailId;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static ru.yamshchik.yamshchik.TestData.NOW;


@ExtendWith(MockitoExtension.class)
@DisplayName("Публикация событий о доставке")
class DeliveryEventPublishingServiceTest {

    private static final int BATCH_SIZE = 10;

    private static final Duration LEASE = Duration.ofMinutes(1);

    @Mock
    private DeliveryEventOutbox outbox;

    @Mock
    private DeliveryEventPublisher publisher;

    private DeliveryEventPublishingService service;

    @BeforeEach
    void setUp() {
        EventProperties events = new EventProperties();
        events.setBatchSize(BATCH_SIZE);
        events.setLease(LEASE);
        YamshchikProperties properties = new YamshchikProperties();
        properties.setEvents(events);
        service = new DeliveryEventPublishingService(outbox, publisher, properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("Публиковать нечего: брокер не трогается")
    void nothingToPublish() {
        when(outbox.claim(BATCH_SIZE, NOW, LEASE)).thenReturn(List.of());

        assertThat(service.publishNext()).isZero();

        verifyNoInteractions(publisher);
    }

    @Test
    @DisplayName("События отмечаются опубликованными только после того, как брокер их принял")
    void publishThenMark() {
        List<DeliveryEvent> events = List.of(event());
        when(outbox.claim(BATCH_SIZE, NOW, LEASE)).thenReturn(events);

        int published = service.publishNext();

        assertThat(published).isEqualTo(1);
        InOrder order = inOrder(publisher, outbox);
        order.verify(publisher).publish(events);
        order.verify(outbox).markPublished(events, NOW);
    }

    @Test
    @DisplayName("Брокер недоступен: события остаются неопубликованными и уйдут позже")
    void brokerUnavailable() {
        List<DeliveryEvent> events = List.of(event());
        when(outbox.claim(BATCH_SIZE, NOW, LEASE)).thenReturn(events);
        doThrow(new ServiceException("broker is down")).when(publisher).publish(events);

        assertThatThrownBy(() -> service.publishNext()).isInstanceOf(ServiceException.class);

        verify(outbox, never()).markPublished(any(), any());
    }

    private static DeliveryEvent event() {
        return DeliveryEvent.of(TestData.pendingDelivery(EmailId.newId(), "user@example.org").undelivered("550", NOW));
    }
}
