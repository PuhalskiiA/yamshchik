package ru.yamshchik.yamshchik.application.service.retry;

import lombok.RequiredArgsConstructor;
import ru.yamshchik.yamshchik.domain.DeliveryFailure;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.random.RandomGenerator;


/**
 * Повторяет только временные отказы; пауза удваивается с каждой попыткой до заданного предела.
 */
@RequiredArgsConstructor
public class ExponentialBackoffRetryPolicy implements RetryPolicy {

    // Дальше удвоение переполнило бы long; пауза к этому времени давно упирается в предел
    private static final int MAX_DOUBLINGS = 30;

    private final int maxAttempts;

    private final Duration initialDelay;

    private final Duration maxDelay;

    private final RandomGenerator random;

    @Override
    public Optional<Instant> nextAttemptAt(int attempts, DeliveryFailure failure, Instant now) {
        if (!failure.temporary() || attempts >= maxAttempts) {
            return Optional.empty();
        }
        Duration delay = initialDelay.multipliedBy(1L << Math.min(attempts - 1, MAX_DOUBLINGS));
        if (delay.compareTo(maxDelay) > 0) {
            delay = maxDelay;
        }
        // Случайная половина паузы разводит по времени письма, отложенные одним и тем же сбоем
        long half = delay.toMillis() / 2;
        return Optional.of(now.plusMillis(half + random.nextLong(half + 1)));
    }
}
