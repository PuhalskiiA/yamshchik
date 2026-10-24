package ru.yamshchik.yamshchik.adapter.out.ratelimit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;
import ru.yamshchik.yamshchik.application.service.ratelimit.SendSignal;
import ru.yamshchik.yamshchik.config.properties.DestinationLimitMode;
import ru.yamshchik.yamshchik.config.properties.DestinationLimitProperties;
import ru.yamshchik.yamshchik.config.properties.DestinationRateAlgorithm;
import ru.yamshchik.yamshchik.config.properties.DispatchProperties;
import ru.yamshchik.yamshchik.config.properties.YamshchikProperties;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;


/**
 * Скрипты темпа на сервер получателя проверяются на настоящем Redis из {@code compose.yaml}:
 * {@code docker compose up -d --wait redis}. Без него тесты пропускаются. Каждый тест работает со своими доменами
 * и убирает их за собой, данные стенда не трогаются.
 */
@DisplayName("Темп на сервер получателя в Redis")
class RedisDestinationRateLimiterTest {

    private static final String REDIS_ADDRESS = System.getenv().getOrDefault("YAMSHCHIK_TEST_REDIS",
                                                                             "redis://localhost:6379");

    private static final String KEY_PREFIX = "yamshchik:destination:";

    private static final int CONNECT_TIMEOUT_MILLIS = 500;

    private static RedissonClient redis;

    private final List<RedisDestinationRateLimiter> limiters = new ArrayList<>();

    private final List<String> domains = new ArrayList<>();

    @BeforeAll
    static void connect() {
        assumeTrue(reachable(), "Redis из compose.yaml не запущен: docker compose up -d --wait redis");
        Config config = new Config();
        config.useSingleServer().setAddress(REDIS_ADDRESS);
        redis = Redisson.create(config);
    }

    @AfterAll
    static void disconnect() {
        if (redis != null) {
            redis.shutdown();
        }
    }

    @AfterEach
    void cleanUp() {
        domains.forEach(domain -> redis.getKeys().delete(KEY_PREFIX + domain));
        limiters.forEach(RedisDestinationRateLimiter::close);
    }

    @Test
    @DisplayName("Первое письмо незнакомому серверу уходит сразу, следующее — по темпу")
    void acquireByRate() throws InterruptedException {
        RedisDestinationRateLimiter limiter = limiter(DestinationRateAlgorithm.AIMD);
        String domain = domain();

        assertThat(limiter.tryAcquire(Set.of(domain))).isEmpty();
        Optional<Duration> wait = limiter.tryAcquire(Set.of(domain));

        assertThat(wait)
                .overridingErrorMessage("При темпе 10 в секунду следующее письмо — примерно через 100 мс, а не %s", wait)
                .hasValueSatisfying(duration -> assertThat(duration.toMillis()).isBetween(50L, 110L));
        Thread.sleep(wait.orElseThrow().plusMillis(20));
        assertThat(limiter.tryAcquire(Set.of(domain))).isEmpty();
    }

    @Test
    @DisplayName("Разрешения на несколько серверов берутся разом: все или ни одного")
    void acquireAllOrNothing() {
        RedisDestinationRateLimiter limiter = limiter(DestinationRateAlgorithm.AIMD);
        String busy = domain();
        String free = domain();
        limiter.tryAcquire(Set.of(busy));

        assertThat(limiter.tryAcquire(Set.of(busy, free)))
                .overridingErrorMessage("Пока один сервер не принимает, письмо не должно уходить никому")
                .isPresent();
        assertThat(limiter.tryAcquire(Set.of(free)))
                .overridingErrorMessage("Неудачная попытка не должна тратить разрешение свободного сервера")
                .isEmpty();
    }

    @Test
    @DisplayName("AIMD: просьба сбавить делит темп пополам, повторная в пределах паузы — нет")
    void aimdDecrease() throws InterruptedException {
        RedisDestinationRateLimiter limiter = limiter(DestinationRateAlgorithm.AIMD);
        String domain = domain();

        limiter.onResult(Set.of(domain), SendSignal.THROTTLED);
        limiter.onResult(Set.of(domain), SendSignal.THROTTLED);
        assertThat(rate(domain)).isEqualTo(5);

        Thread.sleep(350);
        limiter.onResult(Set.of(domain), SendSignal.THROTTLED);
        assertThat(rate(domain)).isEqualTo(2.5);
    }

    @Test
    @DisplayName("AIMD: темп растёт на шаг не чаще интервала")
    void aimdIncrease() throws InterruptedException {
        RedisDestinationRateLimiter limiter = limiter(DestinationRateAlgorithm.AIMD);
        String domain = domain();

        limiter.onResult(Set.of(domain), SendSignal.ACCEPTED);
        limiter.onResult(Set.of(domain), SendSignal.ACCEPTED);
        assertThat(rate(domain))
                .overridingErrorMessage("Рост отсчитывается от первого принятого письма — сразу темп расти не должен")
                .isEqualTo(10);

        Thread.sleep(250);
        limiter.onResult(Set.of(domain), SendSignal.ACCEPTED);
        assertThat(rate(domain)).isEqualTo(11);
    }

    @Test
    @DisplayName("Несколько экземпляров подстраивают один и тот же темп, не затирая друг друга")
    void sharedBetweenInstances() {
        RedisDestinationRateLimiter first = limiter(DestinationRateAlgorithm.AIMD);
        RedisDestinationRateLimiter second = limiter(DestinationRateAlgorithm.AIMD);
        String domain = domain();

        first.onResult(Set.of(domain), SendSignal.THROTTLED);

        assertThat(second.tryAcquire(Set.of(domain))).isEmpty();
        assertThat(rate(domain))
                .overridingErrorMessage("Темп, сниженный одним экземпляром, должен действовать и для другого")
                .isEqualTo(5);
    }

    @Test
    @DisplayName("Оценка: на старте темп удваивается за окно без отказов")
    void estimateStartupDoubles() throws InterruptedException {
        RedisDestinationRateLimiter limiter = limiter(DestinationRateAlgorithm.ESTIMATE);
        String domain = domain();

        limiter.onResult(Set.of(domain), SendSignal.ACCEPTED);
        Thread.sleep(250);
        limiter.onResult(Set.of(domain), SendSignal.ACCEPTED);

        assertThat(state(domain)).containsEntry("phase", "startup");
        assertThat(rate(domain)).isEqualTo(20);
    }

    @Test
    @DisplayName("Оценка: отказ на старте ставит темп по измеренному с запасом, а не делит пополам")
    void estimateStartupThrottle() {
        RedisDestinationRateLimiter limiter = limiter(DestinationRateAlgorithm.ESTIMATE);
        String domain = domain();
        // Сервер уже показал, что принимает 8 писем в секунду
        put(domain, Map.of("rate", "16", "phase", "startup", "history", "6,8", "windowStart", "9999999999"));

        limiter.onResult(Set.of(domain), SendSignal.THROTTLED);

        assertThat(rate(domain))
                .overridingErrorMessage("Темп должен встать на 90 %% от наибольшего принятого (8), а не на половину 16")
                .isEqualTo(7.2);
        assertThat(state(domain)).containsEntry("phase", "cruise");
    }

    @Test
    @DisplayName("Оценка: одиночный отказ в крейсерском режиме оценку не снижает, отказы два окна подряд — снижают")
    void estimateCruiseToleratesSingleThrottle() {
        RedisDestinationRateLimiter limiter = limiter(DestinationRateAlgorithm.ESTIMATE);
        String domain = domain();
        put(domain, Map.of("rate", "5", "estimate", "5", "phase", "cruise", "strikes", "0", "throttled", "0",
                           "history", "4,3", "windowStart", "9999999999", "cruiseLeft", "3"));

        limiter.onResult(Set.of(domain), SendSignal.THROTTLED);
        assertThat(rate(domain))
                .overridingErrorMessage("Одиночный отказ — случайность разброса между экземплярами, темп не трогаем")
                .isEqualTo(5);

        // Следующее окно: отказ снова
        put(domain, Map.of("throttled", "0"));
        limiter.onResult(Set.of(domain), SendSignal.THROTTLED);
        assertThat(rate(domain))
                .overridingErrorMessage("Отказы два окна подряд ставят темп на 90 %% от наибольшего принятого (4)")
                .isEqualTo(3.6);
    }

    @Test
    @DisplayName("Оценка: отказ во время пробы возвращает темп к оценке без снижения")
    void estimateProbeThrottle() {
        RedisDestinationRateLimiter limiter = limiter(DestinationRateAlgorithm.ESTIMATE);
        String domain = domain();
        put(domain, Map.of("rate", "6.25", "estimate", "5", "phase", "probe", "throttled", "0",
                           "history", "5", "windowStart", "9999999999"));

        limiter.onResult(Set.of(domain), SendSignal.THROTTLED);

        assertThat(rate(domain))
                .overridingErrorMessage("Проба лишь зашла выше предела — оценка 5 остаётся верной")
                .isEqualTo(5);
        assertThat(state(domain)).containsEntry("phase", "cruise").containsEntry("estimate", "5");
    }

    @Test
    @DisplayName("Отказ не из-за темпа темп не меняет")
    void failedSignalIsIgnored() {
        RedisDestinationRateLimiter limiter = limiter(DestinationRateAlgorithm.ESTIMATE);
        String domain = domain();

        limiter.onResult(Set.of(domain), SendSignal.FAILED);

        assertThat(state(domain)).isEmpty();
    }

    @Test
    @DisplayName("Redis недоступен: ограничитель так и говорит, а разрешения не выдаются")
    void unavailableRedis() {
        RedisDestinationRateLimiter limiter = limiter(DestinationRateAlgorithm.ESTIMATE, "redis://localhost:1");

        assertThat(limiter.available())
                .overridingErrorMessage("Без Redis рассылка должна останавливаться, а не идти без ограничения")
                .isFalse();
        assertThatThrownBy(() -> limiter.tryAcquire(Set.of(domain()))).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("Redis доступен — ограничитель готов выдавать разрешения")
    void availableRedis() {
        assertThat(limiter(DestinationRateAlgorithm.AIMD).available()).isTrue();
    }

    private RedisDestinationRateLimiter limiter(DestinationRateAlgorithm algorithm) {
        return limiter(algorithm, REDIS_ADDRESS);
    }

    private RedisDestinationRateLimiter limiter(DestinationRateAlgorithm algorithm, String address) {
        DestinationLimitProperties limit = new DestinationLimitProperties();
        limit.setMode(DestinationLimitMode.REDIS);
        limit.setAlgorithm(algorithm);
        limit.setRedisAddress(address);
        limit.setInitialRate(10);
        limit.setMinRate(0.5);
        limit.setMaxRate(100);
        limit.setBurst(Duration.ofMillis(100));
        limit.setIncrease(1);
        limit.setIncreaseInterval(Duration.ofMillis(200));
        limit.setDecreaseFactor(0.5);
        limit.setDecreaseCooldown(Duration.ofMillis(300));
        limit.setEstimateWindow(Duration.ofMillis(200));
        limit.setEstimateWindowMessages(1);
        limit.setProbeGain(1.25);
        limit.setCruiseWindows(3);
        limit.setBackoff(0.9);
        limit.setHistoryWindows(10);
        limit.setUnavailableWait(Duration.ofSeconds(1));
        DispatchProperties dispatch = new DispatchProperties();
        dispatch.setDestinationLimit(limit);
        YamshchikProperties properties = new YamshchikProperties();
        properties.setDispatch(dispatch);
        RedisDestinationRateLimiter limiter = new RedisDestinationRateLimiter(properties, Clock.systemUTC());
        limiters.add(limiter);
        return limiter;
    }

    private String domain() {
        String domain = UUID.randomUUID() + ".test";
        domains.add(domain);
        return domain;
    }

    private static Map<String, String> state(String domain) {
        RMap<String, String> map = redis.getMap(KEY_PREFIX + domain, StringCodec.INSTANCE);
        return map.readAllMap();
    }

    private static double rate(String domain) {
        return Double.parseDouble(state(domain).get("rate"));
    }

    private static void put(String domain, Map<String, String> fields) {
        RMap<String, String> map = redis.getMap(KEY_PREFIX + domain, StringCodec.INSTANCE);
        map.putAll(fields);
    }

    private static boolean reachable() {
        URI uri = URI.create(REDIS_ADDRESS);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(uri.getHost(), uri.getPort()), CONNECT_TIMEOUT_MILLIS);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
