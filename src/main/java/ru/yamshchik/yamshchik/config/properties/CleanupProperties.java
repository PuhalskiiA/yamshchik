package ru.yamshchik.yamshchik.config.properties;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.util.unit.DataSize;

import java.time.Duration;


/**
 * Уборка хранилища вложений по заполненности.
 */
@Getter
@Setter
@NoArgsConstructor
@ToString
public class CleanupProperties {

    private static final int MAX_PERCENT = 100;

    /**
     * Объём хранилища, отведённый под вложения. У S3 нет общего способа узнать ёмкость, поэтому он задаётся здесь.
     */
    @NotNull(message = "Должен быть задан объём хранилища вложений!")
    private DataSize capacity;

    /**
     * При какой заполненности, в процентах от объёма, начинается уборка.
     */
    @Min(value = 1, message = "Порог уборки хранилища должен быть больше нуля!")
    @Max(value = MAX_PERCENT, message = "Порог уборки хранилища не может быть больше 100 процентов!")
    private int thresholdPercent;

    /**
     * Вложения писем, отправленных раньше этого срока, удаляются первыми и целиком.
     */
    @NotNull(message = "Должен быть задан срок, после которого вложения отправленных писем удаляются!")
    @DurationMin(millis = 1, message = "Срок хранения вложений отправленных писем должен быть больше нуля!")
    private Duration minAge;

    /**
     * Как часто проверяется заполненность.
     */
    @NotNull(message = "Должен быть задан интервал проверки заполненности хранилища!")
    @DurationMin(millis = 1, message = "Интервал проверки заполненности хранилища должен быть больше нуля!")
    private Duration interval;

    @Min(value = 1, message = "Размер пачки уборки хранилища должен быть больше нуля!")
    private int batchSize;

    /**
     * @return сколько байт можно занять, прежде чем начнётся уборка
     */
    public long thresholdBytes() {
        return capacity.toBytes() * thresholdPercent / MAX_PERCENT;
    }
}
