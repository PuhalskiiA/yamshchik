package ru.yamshchik.yamshchik.application.service.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import ru.yamshchik.yamshchik.application.service.ratelimit.SendRateLimiter.Permit;
import ru.yamshchik.yamshchik.config.properties.DispatchProperties;
import ru.yamshchik.yamshchik.config.properties.RateLimitProperties;
import ru.yamshchik.yamshchik.config.properties.YamshchikProperties;
import ru.yamshchik.yamshchik.domain.DeliveryFailure;
import ru.yamshchik.yamshchik.domain.TrafficClass;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static ru.yamshchik.yamshchik.TestData.NOW;


@DisplayName("Ограничение темпа отправки")
class RateLimiterTest {

    private static final double RATE = 2;

    private static final int STEP_MILLIS = 10;

    // Ограничение на случай, когда темп не ограничен
    private static final int MAX_PERMITS = 1000;

    @Test
    @DisplayName("Постоянный темп: за секунду уходит столько писем, сколько задано, не больше")
    void fixedRate() {
        FixedRateLimiter limiter = new FixedRateLimiter(properties(RATE, 0));

        int granted = acquireDuring(limiter, TrafficClass.BULK, true, Duration.ofSeconds(10));

        // Одно письмо уходит сразу после старта, дальше — по два в секунду
        assertThat(granted)
                .overridingErrorMessage("За 10 секунд при темпе 2 в секунду должно уйти 21 письмо, а ушло %d", granted)
                .isEqualTo(21);
    }

    @Test
    @DisplayName("Залп после простоя не превышает запаса темпа")
    void burstIsBounded() {
        FixedRateLimiter limiter = new FixedRateLimiter(properties(RATE, 0));
        limiter.tryAcquire(TrafficClass.BULK, true, NOW);

        int burst = 0;
        Instant afterIdle = NOW.plusSeconds(60);
        while (limiter.tryAcquire(TrafficClass.BULK, true, afterIdle).isPresent()) {
            burst++;
        }

        assertThat(burst)
                .overridingErrorMessage("После минуты простоя залпом можно отправить только секунду темпа, а ушло %d", burst)
                .isEqualTo(2);
    }

    @Test
    @DisplayName("Пока ждут срочные, массовые занимают не больше своей доли темпа")
    void bulkIsLimitedToItsShareWhileUrgentWaits() {
        FixedRateLimiter limiter = new FixedRateLimiter(properties(10, 0.5));

        int bulk = acquireDuring(limiter, TrafficClass.BULK, false, Duration.ofSeconds(10));

        assertThat(bulk)
                .overridingErrorMessage("При доле срочных 0,5 массовые не должны занять больше половины темпа, заняли %d из ~100", bulk)
                .isBetween(48, 52);
    }

    @Test
    @DisplayName("Срочные не упираются в долю массовых и могут занять весь темп")
    void urgentMayUseWholeRate() {
        FixedRateLimiter limiter = new FixedRateLimiter(properties(10, 0.5));

        int urgent = acquireDuring(limiter, TrafficClass.URGENT, false, Duration.ofSeconds(10));

        assertThat(urgent).isBetween(99, 101);
    }

    @Test
    @DisplayName("Срочных нет — массовые занимают весь темп")
    void bulkUsesWholeRateWhenUrgentIdle() {
        FixedRateLimiter limiter = new FixedRateLimiter(properties(10, 0.5));

        int bulk = acquireDuring(limiter, TrafficClass.BULK, true, Duration.ofSeconds(10));

        assertThat(bulk)
                .overridingErrorMessage("Простаивающая доля срочных не должна пропадать, массовых ушло %d", bulk)
                .isBetween(99, 101);
    }

    @Test
    @DisplayName("Неиспользованное разрешение возвращается")
    void releaseReturnsPermit() {
        FixedRateLimiter limiter = new FixedRateLimiter(properties(RATE, 0));
        Permit permit = limiter.tryAcquire(TrafficClass.BULK, true, NOW).orElseThrow();
        assertThat(limiter.tryAcquire(TrafficClass.BULK, true, NOW)).isEmpty();

        limiter.release(permit);

        assertThat(limiter.tryAcquire(TrafficClass.BULK, true, NOW))
                .overridingErrorMessage("Письма в очереди не оказалось — разрешение не должно пропадать")
                .isPresent();
    }

    @Test
    @DisplayName("Ограничитель говорит, сколько ждать следующего разрешения")
    void timeUntilPermit() {
        FixedRateLimiter limiter = new FixedRateLimiter(properties(RATE, 0));
        limiter.tryAcquire(TrafficClass.BULK, true, NOW);

        assertThat(limiter.timeUntilPermit(NOW)).isEqualTo(Duration.ofMillis(500));
        assertThat(limiter.timeUntilPermit(NOW.plusMillis(500))).isZero();
    }

    @Test
    @DisplayName("AIMD: просьба сбавить темп делит его, повторная — только после паузы")
    void aimdDecrease() {
        AimdRateLimiter limiter = new AimdRateLimiter(properties(8, 0));

        limiter.onResult(SendSignal.THROTTLED, NOW);
        limiter.onResult(SendSignal.THROTTLED, NOW.plusSeconds(1));

        assertThat(limiter.rate())
                .overridingErrorMessage("Отказы на письма, ушедшие до снижения, не должны снижать темп повторно")
                .isEqualTo(4);

        limiter.onResult(SendSignal.THROTTLED, NOW.plusSeconds(5));

        assertThat(limiter.rate()).isEqualTo(2);
    }

    @Test
    @DisplayName("AIMD: пока сервер принимает, темп растёт на шаг не чаще заданного интервала")
    void aimdIncrease() {
        AimdRateLimiter limiter = new AimdRateLimiter(properties(2, 0));

        limiter.onResult(SendSignal.ACCEPTED, NOW);
        limiter.onResult(SendSignal.ACCEPTED, NOW.plusSeconds(1));
        limiter.onResult(SendSignal.ACCEPTED, NOW.plusSeconds(5));

        assertThat(limiter.rate()).isEqualTo(3);
    }

    @Test
    @DisplayName("AIMD: темп не выходит за пределы, а отказы не из-за темпа его не меняют")
    void aimdBounds() {
        AimdRateLimiter limiter = new AimdRateLimiter(properties(1, 0));

        limiter.onResult(SendSignal.FAILED, NOW);
        assertThat(limiter.rate()).isEqualTo(1);

        for (int i = 0; i < 10; i++) {
            limiter.onResult(SendSignal.THROTTLED, NOW.plusSeconds(5L * i));
        }
        assertThat(limiter.rate())
                .overridingErrorMessage("Темп не должен падать ниже нижнего предела, иначе отправка встанет")
                .isEqualTo(0.5);
    }

    @Test
    @DisplayName("Без ограничения разрешение есть всегда")
    void unlimited() {
        UnlimitedRateLimiter limiter = new UnlimitedRateLimiter();

        assertThat(acquireDuring(limiter, TrafficClass.BULK, false, Duration.ZERO)).isEqualTo(1000);
        assertThat(limiter.rate()).isInfinite();
    }

    @ParameterizedTest(name = "{0} {1} -> {2}")
    @CsvSource(nullValues = "null", value = {
            "421, 4.7.0, THROTTLED",
            "451, 4.7.1, THROTTLED",
            "421, 4.4.2, THROTTLED",
            "452, 4.5.3, THROTTLED",
            "451, 4.3.0, FAILED",
            "null, null, FAILED"
    })
    @DisplayName("Временный отказ по темпу узнаётся по коду и расширенному статусу")
    void signalOfTemporaryFailure(Integer code, String status, SendSignal expected) {
        assertThat(SendSignal.of(new DeliveryFailure(true, code, status, "reply"))).isEqualTo(expected);
    }

    @Test
    @DisplayName("Принятое письмо и постоянный отказ — не просьба сбавить темп")
    void signalOfOtherOutcomes() {
        assertThat(SendSignal.of(null)).isEqualTo(SendSignal.ACCEPTED);
        assertThat(SendSignal.of(new DeliveryFailure(false, 550, "5.7.1", "denied")))
                .overridingErrorMessage("Постоянный отказ по политике не связан с темпом")
                .isEqualTo(SendSignal.FAILED);
    }

    // Каждые 10 миллисекунд берёт все доступные разрешения и считает, сколько выдано
    private static int acquireDuring(SendRateLimiter limiter, TrafficClass trafficClass, boolean urgentIdle,
                                     Duration period) {
        int granted = 0;
        for (long millis = 0; millis <= period.toMillis(); millis += STEP_MILLIS) {
            Instant now = NOW.plusMillis(millis);
            while (granted < MAX_PERMITS && limiter.tryAcquire(trafficClass, urgentIdle, now).isPresent()) {
                granted++;
            }
        }
        return granted;
    }

    private static YamshchikProperties properties(double rate, double urgentShare) {
        RateLimitProperties limit = new RateLimitProperties();
        limit.setRate(rate);
        limit.setUrgentShare(urgentShare);
        limit.setBurst(Duration.ofSeconds(1));
        limit.setMinRate(0.5);
        limit.setMaxRate(100);
        limit.setIncrease(1);
        limit.setIncreaseInterval(Duration.ofSeconds(5));
        limit.setDecreaseFactor(0.5);
        limit.setDecreaseCooldown(Duration.ofSeconds(5));
        DispatchProperties dispatch = new DispatchProperties();
        dispatch.setRateLimit(limit);
        YamshchikProperties properties = new YamshchikProperties();
        properties.setDispatch(dispatch);
        return properties;
    }
}
