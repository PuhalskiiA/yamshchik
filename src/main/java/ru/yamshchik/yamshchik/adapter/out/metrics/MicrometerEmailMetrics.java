package ru.yamshchik.yamshchik.adapter.out.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;
import ru.yamshchik.yamshchik.application.port.out.EmailMetrics;
import ru.yamshchik.yamshchik.application.port.out.EmailRepository;
import ru.yamshchik.yamshchik.domain.DeliveryFailure;
import ru.yamshchik.yamshchik.domain.EmailState;
import ru.yamshchik.yamshchik.domain.EmailStatus;

import java.time.Duration;
import java.util.Locale;


@Component
public class MicrometerEmailMetrics implements EmailMetrics {

    private static final String ACCEPTED_COUNTER = "yamshchik.emails.accepted";

    private static final String ATTEMPT_TIMER = "yamshchik.dispatch.attempts";

    private static final String DELIVERY_TIMER = "yamshchik.emails.delivery.time";

    private static final String STATUS_GAUGE = "yamshchik.emails.by.status";

    private static final String OUTCOME_TAG = "outcome";

    private static final String REPLY_TAG = "reply";

    private static final String STATUS_TAG = "status";

    private static final String REPLY_ACCEPTED = "accepted";

    // Отказ без кода ответа: сбой соединения, таймаут, недоступное хранилище вложений
    private static final String REPLY_NONE = "none";

    private static final String REPLY_CLASS_SUFFIX = "xx";

    private static final int REPLY_CLASS_DIVISOR = 100;

    private final MeterRegistry registry;

    private final Counter accepted;

    private final Timer deliveryTime;

    public MicrometerEmailMetrics(MeterRegistry registry, EmailRepository emailRepository) {
        this.registry = registry;
        this.accepted = Counter.builder(ACCEPTED_COUNTER)
                .description("Писем принято на отправку")
                .register(registry);
        this.deliveryTime = Timer.builder(DELIVERY_TIMER)
                .description("Время от приёма письма до приёма его почтовым сервером")
                .publishPercentileHistogram()
                .register(registry);
        for (EmailStatus status : EmailStatus.values()) {
            Gauge.builder(STATUS_GAUGE, () -> emailRepository.countByStatus(status))
                    .description("Писем в каждом статусе")
                    .tag(STATUS_TAG, status.name())
                    .register(registry);
        }
    }

    @Override
    public void accepted() {
        accepted.increment();
    }

    @Override
    public void attemptCompleted(EmailState result, DeliveryFailure failure, Duration transportTime) {
        Timer.builder(ATTEMPT_TIMER)
                .description("Попытки отправки и длительность передачи письма почтовому серверу")
                .tag(OUTCOME_TAG, result.status().name().toLowerCase(Locale.ROOT))
                .tag(REPLY_TAG, replyClass(failure))
                .publishPercentileHistogram()
                .register(registry)
                .record(transportTime);
        if (result.status() == EmailStatus.SENT) {
            deliveryTime.record(Duration.between(result.createdAt(), result.sentAt()));
        }
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
