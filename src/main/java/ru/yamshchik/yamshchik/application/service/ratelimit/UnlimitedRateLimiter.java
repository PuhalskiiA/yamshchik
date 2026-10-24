package ru.yamshchik.yamshchik.application.service.ratelimit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import ru.yamshchik.yamshchik.config.properties.RateLimitProperties;
import ru.yamshchik.yamshchik.domain.TrafficClass;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;


/**
 * Без ограничения темпа — базовая линия для сравнения: письма уходят так быстро, как успевают обработчики.
 */
@Component
@ConditionalOnProperty(name = RateLimitProperties.STRATEGY_PROPERTY, havingValue = RateLimitProperties.STRATEGY_NONE)
public class UnlimitedRateLimiter implements SendRateLimiter {

    @Override
    public Optional<Permit> tryAcquire(TrafficClass trafficClass, boolean urgentIdle, Instant now) {
        return Optional.of(new Permit(trafficClass));
    }

    @Override
    public void release(Permit permit) {
        // Разрешения не считаются — возвращать нечего
    }

    @Override
    public void onResult(SendSignal signal, Instant now) {
        // Темп не меняется
    }

    @Override
    public double rate() {
        return Double.POSITIVE_INFINITY;
    }

    @Override
    public Duration timeUntilPermit(Instant now) {
        return Duration.ZERO;
    }
}
