package ru.yamshchik.yamshchik.config.properties;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.validator.constraints.time.DurationMin;

import java.time.Duration;


/**
 * Публикация событий о доставке в Kafka.
 */
@Getter
@Setter
@NoArgsConstructor
@ToString
public class EventProperties {

    @NotBlank(message = "Должны быть заданы адреса брокеров Kafka для событий о доставке!")
    private String bootstrapServers;

    @NotBlank(message = "Должен быть задан топик Kafka для событий о доставке!")
    private String topic;

    /**
     * Пауза между проверками неопубликованных событий.
     */
    @NotNull(message = "Должен быть задан интервал публикации событий о доставке!")
    @DurationMin(millis = 1, message = "Интервал публикации событий о доставке должен быть больше нуля!")
    private Duration pollInterval;

    @Min(value = 1, message = "Размер пачки событий о доставке должен быть больше нуля!")
    private int batchSize;

    /**
     * На сколько пачка закрепляется за публикующим. Должно быть больше самой долгой публикации,
     * иначе ту же пачку параллельно опубликует другой экземпляр.
     */
    @NotNull(message = "Должен быть задан срок аренды пачки событий о доставке!")
    @DurationMin(millis = 1, message = "Срок аренды пачки событий о доставке должен быть больше нуля!")
    private Duration lease;
}
