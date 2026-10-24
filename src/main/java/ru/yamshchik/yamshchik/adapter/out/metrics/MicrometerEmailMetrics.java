package ru.yamshchik.yamshchik.adapter.out.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;
import ru.yamshchik.yamshchik.application.port.out.DeliveryEventOutbox;
import ru.yamshchik.yamshchik.application.port.out.EmailMetrics;
import ru.yamshchik.yamshchik.application.port.out.EmailRepository;
import ru.yamshchik.yamshchik.application.port.out.StoredAttachments;
import ru.yamshchik.yamshchik.application.service.ratelimit.SendRateLimiter;
import ru.yamshchik.yamshchik.application.service.ratelimit.SendSignal;
import ru.yamshchik.yamshchik.domain.DeliveryFailure;
import ru.yamshchik.yamshchik.domain.DeliveryStatus;
import ru.yamshchik.yamshchik.domain.EmailState;
import ru.yamshchik.yamshchik.domain.EmailStatus;
import ru.yamshchik.yamshchik.domain.ReportAction;
import ru.yamshchik.yamshchik.domain.TrafficClass;

import java.time.Clock;
import java.time.Duration;
import java.util.Locale;


/**
 * Показатели для Prometheus. Счётчики и таймеры пишутся по событиям, а показатели состояния
 * (сколько писем в каждом статусе, сколько событий не опубликовано) читаются из БД при каждом сборе.
 */
@Component
public class MicrometerEmailMetrics implements EmailMetrics {

    private static final String ACCEPTED_COUNTER = "yamshchik.emails.accepted";

    private static final String ATTEMPT_TIMER = "yamshchik.dispatch.attempts";

    private static final String DELIVERY_TIMER = "yamshchik.emails.delivery.time";

    private static final String STATUS_GAUGE = "yamshchik.emails.by.status";

    private static final String WAITING_GAUGE = "yamshchik.emails.waiting";

    private static final String DELIVERY_STATUS_GAUGE = "yamshchik.deliveries.by.status";

    private static final String POSTPONED_COUNTER = "yamshchik.dispatch.postponed";

    private static final String REPORT_TIMER = "yamshchik.deliveries.report.time";

    private static final String INCIDENT_COUNTER = "yamshchik.deliveries.incidents";

    private static final String UNPUBLISHED_GAUGE = "yamshchik.events.unpublished";

    private static final String UNPUBLISHED_AGE_GAUGE = "yamshchik.events.unpublished.age";

    private static final String RATE_GAUGE = "yamshchik.dispatch.rate.limit";

    private static final String STORED_BYTES_GAUGE = "yamshchik.attachments.stored.bytes";

    private static final String DELETED_COUNTER = "yamshchik.attachments.deleted";

    private static final String DELETED_BYTES_COUNTER = "yamshchik.attachments.deleted.size";

    private static final String OUTCOME_TAG = "outcome";

    private static final String REPLY_TAG = "reply";

    private static final String SIGNAL_TAG = "signal";

    private static final String CLASS_TAG = "class";

    private static final String STATUS_TAG = "status";

    private static final String ACTION_TAG = "action";

    private static final String FORCED_TAG = "forced";

    private static final String REPLY_ACCEPTED = "accepted";

    // Отказ без кода ответа: сбой соединения, таймаут, недоступное хранилище вложений
    private static final String REPLY_NONE = "none";

    private static final String REPLY_CLASS_SUFFIX = "xx";

    private static final int REPLY_CLASS_DIVISOR = 100;

    private static final double MILLIS_PER_SECOND = 1000d;

    private final MeterRegistry registry;

    private final Counter accepted;

    private final Counter incidents;

    public MicrometerEmailMetrics(
            MeterRegistry registry,
            EmailRepository emailRepository,
            DeliveryEventOutbox outbox,
            StoredAttachments storedAttachments,
            SendRateLimiter rateLimiter,
            Clock clock
    ) {
        this.registry = registry;
        this.accepted = Counter.builder(ACCEPTED_COUNTER)
                .description("Писем принято на отправку")
                .register(registry);
        this.incidents = Counter.builder(INCIDENT_COUNTER)
                .description("Инцидентов: отчёт о важном письме не пришёл вовремя")
                .register(registry);
        for (EmailStatus status : EmailStatus.values()) {
            Gauge.builder(STATUS_GAUGE, () -> emailRepository.countByStatus(status))
                    .description("Писем в каждом статусе")
                    .tag(STATUS_TAG, status.name())
                    .register(registry);
        }
        for (TrafficClass trafficClass : TrafficClass.values()) {
            Gauge.builder(WAITING_GAUGE, () -> emailRepository.countWaiting(trafficClass))
                    .description("Писем, ждущих отправки, по классам трафика")
                    .tag(CLASS_TAG, tag(trafficClass))
                    .register(registry);
        }
        for (DeliveryStatus status : DeliveryStatus.values()) {
            Gauge.builder(DELIVERY_STATUS_GAUGE, () -> emailRepository.countDeliveriesByStatus(status))
                    .description("Доставок получателям в каждом статусе")
                    .tag(STATUS_TAG, status.name())
                    .register(registry);
        }
        Gauge.builder(UNPUBLISHED_GAUGE, outbox::countUnpublished)
                .description("Событий о доставке, ещё не опубликованных в Kafka")
                .register(registry);
        Gauge.builder(UNPUBLISHED_AGE_GAUGE,
                      () -> outbox.oldestUnpublished()
                              .map(oldest -> Duration.between(oldest, clock.instant()).toMillis() / MILLIS_PER_SECOND)
                              .orElse(0d))
                .description("Возраст самого старого неопубликованного события, секунды")
                .baseUnit("seconds")
                .register(registry);
        Gauge.builder(RATE_GAUGE, rateLimiter::rate)
                .description("Текущий предел темпа отправки, писем в секунду")
                .register(registry);
        Gauge.builder(STORED_BYTES_GAUGE, storedAttachments::usedBytes)
                .description("Сколько занимает содержимое вложений в хранилище")
                .baseUnit("bytes")
                .register(registry);
    }

    @Override
    public void accepted() {
        accepted.increment();
    }

    @Override
    public void attemptCompleted(
            EmailState result,
            TrafficClass trafficClass,
            DeliveryFailure failure,
            Duration transportTime
    ) {
        Timer.builder(ATTEMPT_TIMER)
                .description("Попытки отправки и длительность передачи письма почтовому серверу")
                .tag(OUTCOME_TAG, result.status().name().toLowerCase(Locale.ROOT))
                .tag(REPLY_TAG, replyClass(failure))
                .tag(SIGNAL_TAG, SendSignal.of(failure).name().toLowerCase(Locale.ROOT))
                .tag(CLASS_TAG, tag(trafficClass))
                .publishPercentileHistogram()
                .register(registry)
                .record(transportTime);
        if (result.status() == EmailStatus.SENT) {
            Timer.builder(DELIVERY_TIMER)
                    .description("Время от приёма письма до приёма его почтовым сервером")
                    .tag(CLASS_TAG, tag(trafficClass))
                    .publishPercentileHistogram()
                    .register(registry)
                    .record(Duration.between(result.createdAt(), result.sentAt()));
        }
    }

    @Override
    public void postponed(TrafficClass trafficClass) {
        Counter.builder(POSTPONED_COUNTER)
                .description("Писем, возвращённых в ожидание: сервер получателя сейчас не принимает по темпу")
                .tag(CLASS_TAG, tag(trafficClass))
                .register(registry)
                .increment();
    }

    @Override
    public void reportApplied(ReportAction action, TrafficClass trafficClass, Duration sinceSent) {
        Timer.builder(REPORT_TIMER)
                .description("Время от приёма письма сервером до отчёта о доставке")
                .tag(ACTION_TAG, action.name().toLowerCase(Locale.ROOT))
                .tag(CLASS_TAG, tag(trafficClass))
                .publishPercentileHistogram()
                .register(registry)
                .record(sinceSent);
    }

    @Override
    public void incident() {
        incidents.increment();
    }

    @Override
    public void attachmentDeleted(boolean forced, long bytes) {
        Counter.builder(DELETED_COUNTER)
                .description("Вложений удалено уборкой хранилища; forced — удалено не по сроку, а из-за нехватки места")
                .tag(FORCED_TAG, Boolean.toString(forced))
                .register(registry)
                .increment();
        Counter.builder(DELETED_BYTES_COUNTER)
                .description("Сколько байт освободила уборка хранилища")
                .baseUnit("bytes")
                .tag(FORCED_TAG, Boolean.toString(forced))
                .register(registry)
                .increment(bytes);
    }

    private static String tag(TrafficClass trafficClass) {
        return trafficClass.name().toLowerCase(Locale.ROOT);
    }

    private static String replyClass(DeliveryFailure failure) {
        if (failure == null) {
            return REPLY_ACCEPTED;
        }
        return failure.replyCode() == null
                ? REPLY_NONE
                : failure.replyCode() / REPLY_CLASS_DIVISOR + REPLY_CLASS_SUFFIX;
    }
}
