package ru.yamshchik.yamshchik.application.service.retry;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import ru.yamshchik.yamshchik.config.properties.RetryProperties;
import ru.yamshchik.yamshchik.config.properties.YamshchikProperties;
import ru.yamshchik.yamshchik.domain.DeliveryFailure;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.random.RandomGenerator;


/**
 * Повторяет только временные отказы; пауза удваивается с каждой попыткой до заданного предела.
 */
@Component
@ConditionalOnProperty(name = RetryProperties.POLICY_PROPERTY, havingValue = RetryProperties.POLICY_EXPONENTIAL)
public class ExponentialBackoffRetryPolicy implements RetryPolicy {

    // Дальше удвоение переполнило бы long; пауза к этому времени давно упирается в предел
    private static final int MAX_DOUBLINGS = 30;

    private final RandomGenerator random = RandomGenerator.getDefault();

    private final RetryProperties retry;

    public ExponentialBackoffRetryPolicy(YamshchikProperties properties) {
        this.retry = properties.getDispatch().getRetry();
    }

    @Override
    public Optional<Instant> nextAttemptAt(int attempts, DeliveryFailure failure, Instant now) {
        if (!failure.temporary() || attempts >= retry.getMaxAttempts()) {
            return Optional.empty();
        }
        Duration delay = retry.getInitialDelay().multipliedBy(1L << Math.min(attempts - 1, MAX_DOUBLINGS));
        if (delay.compareTo(retry.getMaxDelay()) > 0) {
            delay = retry.getMaxDelay();
        }
        // Случайная половина паузы разводит по времени письма, отложенные одним и тем же сбоем
        long half = delay.toMillis() / 2;
        return Optional.of(now.plusMillis(half + random.nextLong(half + 1)));
    }
}
