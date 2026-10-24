package ru.yamshchik.yamshchik.application.service.ratelimit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import ru.yamshchik.yamshchik.config.properties.RateLimitProperties;
import ru.yamshchik.yamshchik.config.properties.YamshchikProperties;

import java.time.Instant;


/**
 * Постоянный темп из настройки — как лимит, который администратор подбирает под почтовый сервер вручную.
 */
@Component
@ConditionalOnProperty(name = RateLimitProperties.STRATEGY_PROPERTY, havingValue = RateLimitProperties.STRATEGY_FIXED)
public class FixedRateLimiter extends TokenBucketRateLimiter {

    private final double rate;

    public FixedRateLimiter(YamshchikProperties properties) {
        super(properties.getDispatch().getRateLimit());
        this.rate = properties.getDispatch().getRateLimit().getRate();
    }

    @Override
    public void onResult(SendSignal signal, Instant now) {
        // Темп не меняется
    }

    @Override
    protected double currentRate() {
        return rate;
    }
}
