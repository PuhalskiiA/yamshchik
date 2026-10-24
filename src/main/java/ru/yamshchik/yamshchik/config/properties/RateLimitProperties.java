package ru.yamshchik.yamshchik.config.properties;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.validator.constraints.time.DurationMin;

import java.time.Duration;


/**
 * Ограничение темпа отправки. Темп задаётся в письмах в секунду и делится между классами трафика:
 * срочным гарантирована доля {@link #urgentShare}, массовые занимают её, только пока срочных нет.
 */
@Getter
@Setter
@NoArgsConstructor
@ToString
public class RateLimitProperties {

    public static final String STRATEGY_PROPERTY = "yamshchik.dispatch.rate-limit.strategy";

    public static final String STRATEGY_NONE = "none";

    public static final String STRATEGY_FIXED = "fixed";

    public static final String STRATEGY_AIMD = "aimd";

    private static final String MIN_POSITIVE = "0.0001";

    @NotNull(message = "Должна быть выбрана стратегия ограничения темпа отправки!")
    private RateLimitStrategy strategy;

    /**
     * Темп, писем в секунду: постоянный для {@code fixed}, начальный для {@code aimd}.
     */
    @DecimalMin(value = MIN_POSITIVE, message = "Темп отправки должен быть больше нуля!")
    private double rate;

    /**
     * Доля темпа, гарантированная срочным письмам.
     */
    @DecimalMin(value = "0", message = "Доля срочных писем не может быть отрицательной!")
    @DecimalMax(value = "1", message = "Доля срочных писем не может быть больше единицы!")
    private double urgentShare;

    /**
     * Сколько секунд накопленного темпа можно отправить залпом после простоя.
     */
    @NotNull(message = "Должен быть задан запас темпа на залп!")
    @DurationMin(millis = 1, message = "Запас темпа на залп должен быть больше нуля!")
    private Duration burst;

    /**
     * Нижний предел темпа для {@code aimd}: ниже темп не падает, сколько бы сервер ни отказывал.
     */
    @DecimalMin(value = MIN_POSITIVE, message = "Нижний предел темпа должен быть больше нуля!")
    private double minRate;

    /**
     * Верхний предел темпа для {@code aimd}.
     */
    @DecimalMin(value = MIN_POSITIVE, message = "Верхний предел темпа должен быть больше нуля!")
    private double maxRate;

    /**
     * На сколько писем в секунду {@code aimd} поднимает темп за шаг, пока сервер принимает письма.
     */
    @DecimalMin(value = MIN_POSITIVE, message = "Шаг роста темпа должен быть больше нуля!")
    private double increase;

    /**
     * Как часто {@code aimd} поднимает темп.
     */
    @NotNull(message = "Должен быть задан интервал роста темпа!")
    @DurationMin(millis = 1, message = "Интервал роста темпа должен быть больше нуля!")
    private Duration increaseInterval;

    /**
     * Во сколько раз {@code aimd} снижает темп, когда сервер просит сбавить.
     */
    @DecimalMin(value = MIN_POSITIVE, message = "Множитель снижения темпа должен быть больше нуля!")
    @DecimalMax(value = "0.9999", message = "Множитель снижения темпа должен быть меньше единицы!")
    private double decreaseFactor;

    /**
     * Сколько {@code aimd} ждёт после снижения, прежде чем снизить ещё раз: отказы на письма,
     * ушедшие до снижения, не должны снижать темп повторно.
     */
    @NotNull(message = "Должна быть задана пауза между снижениями темпа!")
    @DurationMin(millis = 1, message = "Пауза между снижениями темпа должна быть больше нуля!")
    private Duration decreaseCooldown;

    @AssertTrue(message = "Темп отправки должен лежать между нижним и верхним пределом!")
    public boolean isRateWithinBounds() {
        return minRate <= rate && rate <= maxRate;
    }
}
