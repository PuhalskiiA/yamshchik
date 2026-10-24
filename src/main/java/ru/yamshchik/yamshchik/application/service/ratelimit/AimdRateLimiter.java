package ru.yamshchik.yamshchik.application.service.ratelimit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import ru.yamshchik.yamshchik.config.properties.RateLimitProperties;
import ru.yamshchik.yamshchik.config.properties.YamshchikProperties;

import java.time.Duration;
import java.time.Instant;


/**
 * Темп по обратной связи от сервера (AIMD, как в управлении перегрузкой TCP): пока сервер принимает письма,
 * темп растёт на постоянный шаг; когда сервер просит сбавить, темп умножается на коэффициент меньше единицы.
 * Отказы, не связанные с темпом, на него не влияют.
 */
@Component
@ConditionalOnProperty(name = RateLimitProperties.STRATEGY_PROPERTY, havingValue = RateLimitProperties.STRATEGY_AIMD)
@Slf4j
public class AimdRateLimiter extends TokenBucketRateLimiter {

    private final double minRate;

    private final double maxRate;

    private final double increase;

    private final Duration increaseInterval;

    private final double decreaseFactor;

    private final Duration decreaseCooldown;

    private double rate;

    private Instant increasedAt;

    private Instant decreasedAt;

    public AimdRateLimiter(YamshchikProperties properties) {
        super(properties.getDispatch().getRateLimit());
        RateLimitProperties limit = properties.getDispatch().getRateLimit();
        this.rate = limit.getRate();
        this.minRate = limit.getMinRate();
        this.maxRate = limit.getMaxRate();
        this.increase = limit.getIncrease();
        this.increaseInterval = limit.getIncreaseInterval();
        this.decreaseFactor = limit.getDecreaseFactor();
        this.decreaseCooldown = limit.getDecreaseCooldown();
    }

    @Override
    public synchronized void onResult(SendSignal signal, Instant now) {
        switch (signal) {
            case ACCEPTED -> increase(now);
            case THROTTLED -> decrease(now);
            case FAILED -> {
                // Отказ не из-за темпа: сигнала для регулятора нет
            }
        }
    }

    @Override
    protected double currentRate() {
        return rate;
    }

    // Отсчёт интервала начинается с первого принятого письма: темп растёт, только когда сервер принимал его какое-то время
    private void increase(Instant now) {
        if (increasedAt == null) {
            increasedAt = now;
            return;
        }
        if (Duration.between(increasedAt, now).compareTo(increaseInterval) < 0) {
            return;
        }
        increasedAt = now;
        change(Math.min(maxRate, rate + increase), now);
    }

    private void decrease(Instant now) {
        if (decreasedAt != null && Duration.between(decreasedAt, now).compareTo(decreaseCooldown) < 0) {
            return;
        }
        decreasedAt = now;
        // После снижения темп растёт не сразу: сначала сервер должен принять письма уже на новом темпе
        increasedAt = now;
        double previous = rate;
        change(Math.max(minRate, rate * decreaseFactor), now);
        log.info("Send rate decreased from {} to {} messages per second", previous, rate);
    }

    private void change(double newRate, Instant now) {
        refill(now);
        rate = newRate;
    }
}
