package ru.yamshchik.yamshchik.adapter.out.ratelimit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import ru.yamshchik.yamshchik.application.port.out.DestinationRateLimiter;
import ru.yamshchik.yamshchik.application.service.ratelimit.SendSignal;
import ru.yamshchik.yamshchik.config.properties.DestinationLimitProperties;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;


/**
 * Темп на сервер получателя не ограничивается.
 */
@Component
@ConditionalOnProperty(name = DestinationLimitProperties.MODE_PROPERTY, havingValue = DestinationLimitProperties.MODE_NONE)
public class NoDestinationRateLimiter implements DestinationRateLimiter {

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public Optional<Duration> tryAcquire(Set<String> domains) {
        return Optional.empty();
    }

    @Override
    public void onResult(Set<String> domains, SendSignal signal) {
        // Темп не подстраивается
    }
}
