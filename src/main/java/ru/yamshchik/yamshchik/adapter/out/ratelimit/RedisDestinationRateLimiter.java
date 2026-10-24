package ru.yamshchik.yamshchik.adapter.out.ratelimit;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import ru.yamshchik.yamshchik.application.port.out.DestinationRateLimiter;
import ru.yamshchik.yamshchik.application.service.ratelimit.SendSignal;
import ru.yamshchik.yamshchik.config.exception.type.ServiceException;
import ru.yamshchik.yamshchik.config.properties.DestinationLimitProperties;
import ru.yamshchik.yamshchik.config.properties.DestinationRateAlgorithm;
import ru.yamshchik.yamshchik.config.properties.YamshchikProperties;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;


/**
 * Темп на каждый сервер получателя хранится в Redis и общий для всех экземпляров. Выдача разрешений
 * и подстройка темпа выполняются скриптами в Redis атомарно, по часам Redis, — экземпляры не затирают
 * изменения друг друга и не зависят от расхождения своих часов.
 */
@Component
@ConditionalOnProperty(name = DestinationLimitProperties.MODE_PROPERTY, havingValue = DestinationLimitProperties.MODE_REDIS)
@Slf4j
public class RedisDestinationRateLimiter implements DestinationRateLimiter {

    private static final String KEY_PREFIX = "yamshchik:destination:";

    private static final String PING_KEY = "yamshchik:ping";

    private static final String ACQUIRE_SCRIPT = "redis/acquire.lua";

    private static final String ADJUST_SCRIPT = "redis/adjust.lua";

    private static final String ESTIMATE_SCRIPT = "redis/estimate.lua";

    private static final int TIMEOUT_MILLIS = 1000;

    // Доступность проверяется перед каждым захватом письма; результат проверки живёт недолго
    private static final Duration AVAILABILITY_TTL = Duration.ofMillis(500);

    private static final double MILLIS_PER_SECOND = 1000d;

    private final DestinationLimitProperties limit;

    private final RedissonClient redisson;

    private final String acquireScript;

    private final String adjustScript;

    private final String estimateScript;

    private final Clock clock;

    private volatile Instant checkedAt = Instant.MIN;

    private volatile boolean lastAvailable;

    public RedisDestinationRateLimiter(YamshchikProperties properties, Clock clock) {
        this.limit = properties.getDispatch().getDestinationLimit();
        this.clock = clock;
        this.acquireScript = load(ACQUIRE_SCRIPT);
        this.adjustScript = load(ADJUST_SCRIPT);
        this.estimateScript = load(ESTIMATE_SCRIPT);
        Config config = new Config();
        // Сервис стартует и без Redis: рассылка просто ждёт, пока он появится
        config.setLazyInitialization(true);
        config.useSingleServer()
                .setAddress(limit.getRedisAddress())
                .setConnectTimeout(TIMEOUT_MILLIS)
                .setTimeout(TIMEOUT_MILLIS)
                .setRetryAttempts(0);
        this.redisson = Redisson.create(config);
    }

    @Override
    public boolean available() {
        Instant now = clock.instant();
        if (Duration.between(checkedAt, now).compareTo(AVAILABILITY_TTL) < 0) {
            return lastAvailable;
        }
        boolean available;
        try {
            redisson.getBucket(PING_KEY, StringCodec.INSTANCE).isExists();
            available = true;
        } catch (RuntimeException e) {
            if (lastAvailable || checkedAt == Instant.MIN) {
                log.error("Redis is unavailable, sending is stopped until it is back: {}", e.getMessage());
            }
            available = false;
        }
        if (available && !lastAvailable && checkedAt != Instant.MIN) {
            log.info("Redis is available again, sending is resumed");
        }
        lastAvailable = available;
        checkedAt = now;
        return available;
    }

    @Override
    public Optional<Duration> tryAcquire(Set<String> domains) {
        if (domains.isEmpty()) {
            return Optional.empty();
        }
        List<Object> keys = domains.stream().sorted().<Object>map(RedisDestinationRateLimiter::key).toList();
        Long waitMillis = script().eval(RScript.Mode.READ_WRITE, acquireScript, RScript.ReturnType.LONG, keys,
                                        number(limit.getInitialRate()), number(seconds(limit.getBurst())));
        return waitMillis == null || waitMillis <= 0 ? Optional.empty() : Optional.of(Duration.ofMillis(waitMillis));
    }

    @Override
    public void onResult(Set<String> domains, SendSignal signal) {
        if (signal == SendSignal.FAILED) {
            return;
        }
        String name = signal.name().toLowerCase(Locale.ROOT);
        for (String domain : domains) {
            String rate = limit.getAlgorithm() == DestinationRateAlgorithm.ESTIMATE
                    ? estimate(domain, name)
                    : adjust(domain, name);
            if (signal == SendSignal.THROTTLED) {
                log.info("Server of {} asks to slow down, its rate is {} per second now", domain, rate);
            }
        }
    }

    private String adjust(String domain, String signal) {
        return script().eval(RScript.Mode.READ_WRITE, adjustScript, RScript.ReturnType.STRING,
                             List.of(key(domain)), signal,
                             number(limit.getInitialRate()), number(limit.getMinRate()),
                             number(limit.getMaxRate()), number(limit.getIncrease()),
                             number(seconds(limit.getIncreaseInterval())),
                             number(limit.getDecreaseFactor()),
                             number(seconds(limit.getDecreaseCooldown())),
                             number(seconds(limit.getBurst())));
    }

    private String estimate(String domain, String signal) {
        return script().eval(RScript.Mode.READ_WRITE, estimateScript, RScript.ReturnType.STRING,
                             List.of(key(domain)), signal,
                             number(limit.getInitialRate()), number(limit.getMinRate()),
                             number(limit.getMaxRate()), number(seconds(limit.getBurst())),
                             number(seconds(limit.getEstimateWindow())), number(limit.getEstimateWindowMessages()),
                             number(limit.getProbeGain()), number(limit.getCruiseWindows()),
                             number(limit.getBackoff()), number(limit.getHistoryWindows()));
    }

    @PreDestroy
    public void close() {
        redisson.shutdown();
    }

    private RScript script() {
        return redisson.getScript(StringCodec.INSTANCE);
    }

    private static String key(String domain) {
        return KEY_PREFIX + domain;
    }

    private static double seconds(Duration duration) {
        return duration.toMillis() / MILLIS_PER_SECOND;
    }

    private static String number(double value) {
        return Double.toString(value);
    }

    private static String load(String path) {
        try (InputStream input = new ClassPathResource(path).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ServiceException("Redis script is missing: " + path, e);
        }
    }
}
