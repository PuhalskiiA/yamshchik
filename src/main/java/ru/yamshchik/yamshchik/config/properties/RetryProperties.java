package ru.yamshchik.yamshchik.config.properties;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.validator.constraints.time.DurationMin;

import java.time.Duration;


@Getter
@Setter
@NoArgsConstructor
@ToString
public class RetryProperties {

    public static final String POLICY_PROPERTY = "yamshchik.dispatch.retry.policy";

    public static final String POLICY_EXPONENTIAL = "exponential";

    public static final String POLICY_FIXED = "fixed";

    @NotNull(message = "Должна быть выбрана политика повторов отправки!")
    private RetryPolicyType policy;

    /**
     * Сколько всего попыток отправки делается, считая первую.
     */
    @Min(value = 1, message = "Число попыток отправки должно быть больше нуля!")
    private int maxAttempts;

    /**
     * Пауза перед второй попыткой; при постоянной паузе — пауза перед каждой.
     */
    @NotNull(message = "Должна быть задана начальная пауза между попытками отправки!")
    @DurationMin(millis = 1, message = "Начальная пауза между попытками отправки должна быть больше нуля!")
    private Duration initialDelay;

    /**
     * Предел, до которого растёт пауза. При постоянной паузе не используется.
     */
    @NotNull(message = "Должна быть задана наибольшая пауза между попытками отправки!")
    @DurationMin(millis = 1, message = "Наибольшая пауза между попытками отправки должна быть больше нуля!")
    private Duration maxDelay;
}
