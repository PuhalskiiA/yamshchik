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
 * Темп отправки на каждый сервер получателя (домен), общий для всех экземпляров сервиса. Темп каждого сервера
 * подстраивается по его ответам: растёт на шаг, пока сервер принимает письма, и падает в {@link #decreaseFactor}
 * раз, когда просит сбавить.
 */
@Getter
@Setter
@NoArgsConstructor
@ToString
public class DestinationLimitProperties {

    public static final String MODE_PROPERTY = "yamshchik.dispatch.destination-limit.mode";

    public static final String MODE_NONE = "none";

    public static final String MODE_REDIS = "redis";

    private static final String MIN_POSITIVE = "0.0001";

    @NotNull(message = "Должен быть выбран способ ограничения темпа на сервер получателя!")
    private DestinationLimitMode mode;

    /**
     * Как подстраивается темп сервера по его ответам.
     */
    @NotNull(message = "Должен быть выбран способ подстройки темпа на сервер получателя!")
    private DestinationRateAlgorithm algorithm;

    /**
     * Адрес Redis, например {@code redis://localhost:6379}. Нужен для {@code redis}.
     */
    private String redisAddress;

    /**
     * Темп для сервера, о котором ещё ничего не известно, писем в секунду.
     */
    @DecimalMin(value = MIN_POSITIVE, message = "Начальный темп на сервер получателя должен быть больше нуля!")
    private double initialRate;

    @DecimalMin(value = MIN_POSITIVE, message = "Нижний предел темпа на сервер получателя должен быть больше нуля!")
    private double minRate;

    @DecimalMin(value = MIN_POSITIVE, message = "Верхний предел темпа на сервер получателя должен быть больше нуля!")
    private double maxRate;

    /**
     * Сколько секунд накопленного темпа можно отправить на сервер залпом.
     */
    @NotNull(message = "Должен быть задан запас темпа на залп для сервера получателя!")
    @DurationMin(millis = 1, message = "Запас темпа на залп для сервера получателя должен быть больше нуля!")
    private Duration burst;

    @DecimalMin(value = MIN_POSITIVE, message = "Шаг роста темпа на сервер получателя должен быть больше нуля!")
    private double increase;

    @NotNull(message = "Должен быть задан интервал роста темпа на сервер получателя!")
    @DurationMin(millis = 1, message = "Интервал роста темпа на сервер получателя должен быть больше нуля!")
    private Duration increaseInterval;

    @DecimalMin(value = MIN_POSITIVE, message = "Множитель снижения темпа на сервер получателя должен быть больше нуля!")
    @DecimalMax(value = "0.9999", message = "Множитель снижения темпа на сервер получателя должен быть меньше единицы!")
    private double decreaseFactor;

    @NotNull(message = "Должна быть задана пауза между снижениями темпа на сервер получателя!")
    @DurationMin(millis = 1, message = "Пауза между снижениями темпа на сервер получателя должна быть больше нуля!")
    private Duration decreaseCooldown;

    /**
     * {@code estimate}: наименьшая длина окна, за которое считаются принятые сервером письма.
     */
    @NotNull(message = "Должно быть задано наименьшее окно оценки темпа!")
    @DurationMin(millis = 1, message = "Наименьшее окно оценки темпа должно быть больше нуля!")
    private Duration estimateWindow;

    /**
     * {@code estimate}: сколько писем должно уместиться в окно; при малом темпе окно удлиняется.
     */
    @DecimalMin(value = "1", message = "В окно оценки темпа должно умещаться хотя бы одно письмо!")
    private double estimateWindowMessages;

    /**
     * {@code estimate}: во сколько раз проба поднимает темп над оценкой.
     */
    @DecimalMin(value = "1.0001", message = "Проба должна поднимать темп над оценкой!")
    private double probeGain;

    /**
     * {@code estimate}: сколько окон темп держится по оценке между пробами.
     */
    @DecimalMin(value = "1", message = "Между пробами должно быть хотя бы одно окно!")
    private double cruiseWindows;

    /**
     * {@code estimate}: на какую долю измеренного сервер нагружается после просьбы сбавить — запас под его пределом.
     */
    @DecimalMin(value = MIN_POSITIVE, message = "Запас под пределом сервера должен быть больше нуля!")
    @DecimalMax(value = "1", message = "Запас под пределом сервера не может быть больше единицы!")
    private double backoff;

    /**
     * {@code estimate}: за сколько последних окон помнится наибольшее число принятых сервером писем.
     */
    @DecimalMin(value = "1", message = "Помнить нужно хотя бы одно окно!")
    private double historyWindows;

    /**
     * Через сколько повторить, если Redis недоступен: рассылка стоит, письма не захватываются.
     */
    @NotNull(message = "Должна быть задана пауза при недоступном Redis!")
    @DurationMin(millis = 1, message = "Пауза при недоступном Redis должна быть больше нуля!")
    private Duration unavailableWait;

    @AssertTrue(message = "Для ограничения темпа через Redis должен быть задан адрес Redis!")
    public boolean isRedisConfigured() {
        return mode != DestinationLimitMode.REDIS || (redisAddress != null && !redisAddress.isBlank());
    }

    @AssertTrue(message = "Начальный темп на сервер получателя должен лежать между нижним и верхним пределом!")
    public boolean isInitialRateWithinBounds() {
        return minRate <= initialRate && initialRate <= maxRate;
    }
}
