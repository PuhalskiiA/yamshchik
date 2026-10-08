package ru.yamshchik.yamshchik.config.properties;

import jakarta.validation.Valid;
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
public class DispatchProperties {

    public static final String QUEUE_PROPERTY = "yamshchik.dispatch.queue";

    public static final String QUEUE_POSTGRES = "postgres";

    public static final String QUEUE_MEMORY = "memory";

    /**
     * Реализация очереди отправки.
     */
    @NotNull(message = "Должна быть выбрана реализация очереди отправки!")
    private DispatchQueueType queue;

    /**
     * Пауза между опросами очереди, когда она пуста.
     */
    @NotNull(message = "Должен быть задан интервал опроса очереди отправки!")
    @DurationMin(millis = 1, message = "Интервал опроса очереди отправки должен быть больше нуля!")
    private Duration pollInterval;

    /**
     * Сколько обработчиков одновременно забирают письма из очереди и отправляют их.
     */
    @Min(value = 1, message = "Число обработчиков очереди отправки должно быть больше нуля!")
    private int workers;

    /**
     * На сколько письмо закрепляется за обработчиком, взявшим его в отправку. Если за это время итог
     * не записан, письмо считается брошенным и отдаётся снова. Должно быть больше самой долгой отправки.
     */
    @NotNull(message = "Должен быть задан срок аренды письма обработчиком!")
    @DurationMin(millis = 1, message = "Срок аренды письма обработчиком должен быть больше нуля!")
    private Duration lease;

    /**
     * Повторы отправки после неудачной попытки.
     */
    @NotNull(message = "Обязательно должны быть указаны параметры повторов отправки!")
    @Valid
    private RetryProperties retry;
}
