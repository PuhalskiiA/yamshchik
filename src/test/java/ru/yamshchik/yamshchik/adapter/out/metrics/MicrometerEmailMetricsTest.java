package ru.yamshchik.yamshchik.adapter.out.metrics;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.yamshchik.yamshchik.application.port.out.DeliveryEventOutbox;
import ru.yamshchik.yamshchik.application.port.out.EmailRepository;
import ru.yamshchik.yamshchik.application.port.out.StoredAttachments;
import ru.yamshchik.yamshchik.application.service.ratelimit.SendRateLimiter;
import ru.yamshchik.yamshchik.domain.DeliveryFailure;
import ru.yamshchik.yamshchik.domain.DeliveryStatus;
import ru.yamshchik.yamshchik.domain.EmailId;
import ru.yamshchik.yamshchik.domain.EmailState;
import ru.yamshchik.yamshchik.domain.EmailStatus;
import ru.yamshchik.yamshchik.domain.TrafficClass;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static ru.yamshchik.yamshchik.TestData.LEASE;
import static ru.yamshchik.yamshchik.TestData.NOW;


@ExtendWith(MockitoExtension.class)
@DisplayName("Метрики сервиса")
class MicrometerEmailMetricsTest {

    private static final Duration TRANSPORT_TIME = Duration.ofMillis(120);

    @Mock
    private EmailRepository emailRepository;

    @Mock
    private DeliveryEventOutbox outbox;

    @Mock
    private StoredAttachments storedAttachments;

    @Mock
    private SendRateLimiter rateLimiter;

    private SimpleMeterRegistry registry;

    private MicrometerEmailMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new MicrometerEmailMetrics(registry, emailRepository, outbox, storedAttachments, rateLimiter,
                                             Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("Принятые письма считаются")
    void accepted() {
        metrics.accepted();
        metrics.accepted();

        assertThat(registry.get("yamshchik.emails.accepted").counter().count()).isEqualTo(2);
    }

    @Test
    @DisplayName("Успешная попытка: исход sent, ответ accepted, время доставки записано")
    void sentAttempt() {
        EmailState sent = sending().sent(NOW.plusSeconds(3));

        metrics.attemptCompleted(sent, TrafficClass.URGENT, null, TRANSPORT_TIME);

        Timer attempts = registry.get("yamshchik.dispatch.attempts")
                .tag("outcome", "sent").tag("reply", "accepted").tag("class", "urgent").timer();
        assertThat(attempts.count()).isEqualTo(1);
        assertThat(attempts.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(120);
        Timer delivery = registry.get("yamshchik.emails.delivery.time").tag("class", "urgent").timer();
        assertThat(delivery.totalTime(TimeUnit.SECONDS))
                .overridingErrorMessage("Время доставки считается от приёма письма до приёма его сервером")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("Отказ с кодом ответа попадает в класс 4xx или 5xx")
    void failedAttemptsByReplyClass() {
        metrics.attemptCompleted(sending().deferred("451", NOW, NOW.plusSeconds(30)), TrafficClass.BULK,
                                 new DeliveryFailure(true, 451, "4.7.1", "try later"), TRANSPORT_TIME);
        metrics.attemptCompleted(sending().failed("550", NOW), TrafficClass.BULK,
                                 new DeliveryFailure(false, 550, "5.1.1", "no such user"), TRANSPORT_TIME);

        assertThat(registry.get("yamshchik.dispatch.attempts")
                           .tag("outcome", "deferred").tag("reply", "4xx").timer().count()).isEqualTo(1);
        assertThat(registry.get("yamshchik.dispatch.attempts")
                           .tag("outcome", "failed").tag("reply", "5xx").timer().count()).isEqualTo(1);
        assertThat(registry.find("yamshchik.emails.delivery.time").timer())
                .overridingErrorMessage("Неудачные попытки не должны попадать во время доставки")
                .isNull();
    }

    @Test
    @DisplayName("Показатели состояния читаются из источников при сборе")
    void stateGauges() {
        when(emailRepository.countWaiting(TrafficClass.URGENT)).thenReturn(3L);
        when(emailRepository.countDeliveriesByStatus(DeliveryStatus.SENT)).thenReturn(5L);
        when(outbox.oldestUnpublished()).thenReturn(Optional.of(NOW.minusSeconds(42)));
        when(rateLimiter.rate()).thenReturn(1.5);

        assertThat(registry.get("yamshchik.emails.waiting").tag("class", "urgent").gauge().value())
                .overridingErrorMessage("Очередь срочных писем должна показываться отдельно от массовых")
                .isEqualTo(3);
        assertThat(registry.get("yamshchik.deliveries.by.status").tag("status", "SENT").gauge().value()).isEqualTo(5);
        assertThat(registry.get("yamshchik.events.unpublished.age").gauge().value())
                .overridingErrorMessage("Возраст неопубликованного события считается от момента события до сбора")
                .isEqualTo(42);
        assertThat(registry.get("yamshchik.dispatch.rate.limit").gauge().value()).isEqualTo(1.5);
    }

    @Test
    @DisplayName("Попытка помечается сигналом для регулятора: сервер просит сбавить темп")
    void throttledSignal() {
        metrics.attemptCompleted(sending().deferred("421", NOW, NOW.plusSeconds(30)), TrafficClass.BULK,
                                 new DeliveryFailure(true, 421, "4.7.0", "slow down"), TRANSPORT_TIME);

        assertThat(registry.get("yamshchik.dispatch.attempts").tag("signal", "throttled").timer().count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Отказ без кода ответа помечается reply=none")
    void failureWithoutReplyCode() {
        metrics.attemptCompleted(sending().deferred("timeout", NOW, NOW.plusSeconds(30)), TrafficClass.BULK,
                                 new DeliveryFailure(true, null, null, "timeout"), TRANSPORT_TIME);

        assertThat(registry.get("yamshchik.dispatch.attempts")
                           .tag("outcome", "deferred").tag("reply", "none").timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Число писем по статусам читается из хранилища")
    void emailsByStatus() {
        when(emailRepository.countByStatus(EmailStatus.DEFERRED)).thenReturn(7L);

        double deferred = registry.get("yamshchik.emails.by.status").tag("status", "DEFERRED").gauge().value();

        assertThat(deferred).isEqualTo(7);
        assertThat(registry.find("yamshchik.emails.by.status").gauges())
                .overridingErrorMessage("Показатель должен быть для каждого статуса письма")
                .hasSize(EmailStatus.values().length);
    }

    private static EmailState sending() {
        return EmailState.accepted(EmailId.newId(), NOW).startSending(NOW, LEASE);
    }
}
