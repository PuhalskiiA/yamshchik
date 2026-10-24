package ru.yamshchik.yamshchik.application.service.retry;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import ru.yamshchik.yamshchik.config.properties.RetryProperties;
import ru.yamshchik.yamshchik.config.properties.YamshchikProperties;
import ru.yamshchik.yamshchik.domain.DeliveryFailure;

import java.time.Instant;
import java.util.Optional;


/**
 * Исходный вариант для сравнения: любой отказ повторяется через одну и ту же паузу,
 * временные и постоянные отказы не различаются.
 */
@Component
@ConditionalOnProperty(name = RetryProperties.POLICY_PROPERTY, havingValue = RetryProperties.POLICY_FIXED)
public class FixedDelayRetryPolicy implements RetryPolicy {

    private final RetryProperties retry;

    public FixedDelayRetryPolicy(YamshchikProperties properties) {
        this.retry = properties.getDispatch().getRetry();
    }

    @Override
    public Optional<Instant> nextAttemptAt(int attempts, DeliveryFailure failure, Instant now) {
        return attempts >= retry.getMaxAttempts() ? Optional.empty() : Optional.of(now.plus(retry.getInitialDelay()));
    }
}
