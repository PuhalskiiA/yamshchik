package ru.yamshchik.yamshchik.application.service.retry;

import lombok.RequiredArgsConstructor;
import ru.yamshchik.yamshchik.domain.DeliveryFailure;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;


/**
 * Исходный вариант для сравнения: любой отказ повторяется через одну и ту же паузу,
 * временные и постоянные отказы не различаются.
 */
@RequiredArgsConstructor
public class FixedDelayRetryPolicy implements RetryPolicy {

    private final int maxAttempts;

    private final Duration delay;

    @Override
    public Optional<Instant> nextAttemptAt(int attempts, DeliveryFailure failure, Instant now) {
        return attempts >= maxAttempts ? Optional.empty() : Optional.of(now.plus(delay));
    }
}
