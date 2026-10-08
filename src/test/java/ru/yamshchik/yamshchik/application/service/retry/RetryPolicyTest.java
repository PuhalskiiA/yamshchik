package ru.yamshchik.yamshchik.application.service.retry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import ru.yamshchik.yamshchik.config.properties.DispatchProperties;
import ru.yamshchik.yamshchik.config.properties.RetryProperties;
import ru.yamshchik.yamshchik.config.properties.YamshchikProperties;
import ru.yamshchik.yamshchik.domain.DeliveryFailure;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static ru.yamshchik.yamshchik.TestData.NOW;


@DisplayName("Политики повторов")
class RetryPolicyTest {

    private static final int MAX_ATTEMPTS = 5;

    private static final Duration INITIAL_DELAY = Duration.ofSeconds(30);

    private static final Duration MAX_DELAY = Duration.ofMinutes(10);

    private static final DeliveryFailure TEMPORARY = new DeliveryFailure(true, 451, "4.7.1", "try later");

    private static final DeliveryFailure PERMANENT = new DeliveryFailure(false, 550, "5.1.1", "no such user");

    private final ExponentialBackoffRetryPolicy exponential = new ExponentialBackoffRetryPolicy(properties());

    private final FixedDelayRetryPolicy fixed = new FixedDelayRetryPolicy(properties());

    @Test
    @DisplayName("Растущая пауза: постоянный отказ не повторяется")
    void exponentialDoesNotRetryPermanentFailure() {
        assertThat(exponential.nextAttemptAt(1, PERMANENT, NOW))
                .overridingErrorMessage("Постоянный отказ сервера повторять бессмысленно")
                .isEmpty();
    }

    @Test
    @DisplayName("Растущая пауза: после последней попытки повторов нет")
    void exponentialStopsAtMaxAttempts() {
        assertThat(exponential.nextAttemptAt(MAX_ATTEMPTS, TEMPORARY, NOW))
                .overridingErrorMessage("После %d попыток письмо больше не должно отправляться", MAX_ATTEMPTS)
                .isEmpty();
        assertThat(exponential.nextAttemptAt(MAX_ATTEMPTS - 1, TEMPORARY, NOW)).isPresent();
    }

    @RepeatedTest(20)
    @DisplayName("Растущая пауза: первая пауза лежит между половиной и полной начальной")
    void exponentialFirstDelayWithinJitterRange() {
        Instant next = exponential.nextAttemptAt(1, TEMPORARY, NOW).orElseThrow();

        assertThat(Duration.between(NOW, next))
                .overridingErrorMessage("Первая пауза %s вышла за пределы от %s до %s",
                                        Duration.between(NOW, next), INITIAL_DELAY.dividedBy(2), INITIAL_DELAY)
                .isBetween(INITIAL_DELAY.dividedBy(2), INITIAL_DELAY);
    }

    @RepeatedTest(20)
    @DisplayName("Растущая пауза: с каждой попыткой пауза удваивается")
    void exponentialDelayDoubles() {
        Duration third = Duration.between(NOW, exponential.nextAttemptAt(3, TEMPORARY, NOW).orElseThrow());
        Duration expected = INITIAL_DELAY.multipliedBy(4);

        assertThat(third)
                .overridingErrorMessage("Пауза после третьей попытки должна быть от %s до %s, а получилась %s",
                                        expected.dividedBy(2), expected, third)
                .isBetween(expected.dividedBy(2), expected);
    }

    @RepeatedTest(20)
    @DisplayName("Растущая пауза: пауза не превышает предел")
    void exponentialDelayIsCapped() {
        ExponentialBackoffRetryPolicy manyAttempts = new ExponentialBackoffRetryPolicy(properties(100));

        Duration delay = Duration.between(NOW, manyAttempts.nextAttemptAt(60, TEMPORARY, NOW).orElseThrow());

        assertThat(delay)
                .overridingErrorMessage("Пауза %s превысила предел %s или переполнилась", delay, MAX_DELAY)
                .isBetween(MAX_DELAY.dividedBy(2), MAX_DELAY);
    }

    @Test
    @DisplayName("Постоянная пауза: любой отказ повторяется через одно и то же время")
    void fixedRetriesAnyFailure() {
        Optional<Instant> afterTemporary = fixed.nextAttemptAt(1, TEMPORARY, NOW);
        Optional<Instant> afterPermanent = fixed.nextAttemptAt(2, PERMANENT, NOW);

        assertThat(afterTemporary).contains(NOW.plus(INITIAL_DELAY));
        assertThat(afterPermanent)
                .overridingErrorMessage("Исходная политика не различает отказы и должна повторять и постоянный")
                .contains(NOW.plus(INITIAL_DELAY));
    }

    @Test
    @DisplayName("Постоянная пауза: после последней попытки повторов нет")
    void fixedStopsAtMaxAttempts() {
        assertThat(fixed.nextAttemptAt(MAX_ATTEMPTS, TEMPORARY, NOW)).isEmpty();
    }

    private static YamshchikProperties properties() {
        return properties(MAX_ATTEMPTS);
    }

    private static YamshchikProperties properties(int maxAttempts) {
        RetryProperties retry = new RetryProperties();
        retry.setMaxAttempts(maxAttempts);
        retry.setInitialDelay(INITIAL_DELAY);
        retry.setMaxDelay(MAX_DELAY);
        DispatchProperties dispatch = new DispatchProperties();
        dispatch.setRetry(retry);
        YamshchikProperties properties = new YamshchikProperties();
        properties.setDispatch(dispatch);
        return properties;
    }
}
