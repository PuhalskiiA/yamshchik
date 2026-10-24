package ru.yamshchik.yamshchik.config.properties;

import jakarta.validation.constraints.Max;
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
 * Чтение отчётов о доставке из ящика учётной записи сервиса. Вход — под той же учётной записью,
 * от которой идёт отправка ({@code spring.mail.username} и {@code spring.mail.password}).
 */
@Getter
@Setter
@NoArgsConstructor
@ToString
public class ReportProperties {

    private static final int MAX_PORT = 65535;

    /**
     * Адрес сервера IMAP.
     */
    @NotBlank(message = "Должен быть задан адрес сервера IMAP с отчётами о доставке!")
    private String host;

    @Min(value = 1, message = "Порт сервера IMAP должен быть больше нуля!")
    @Max(value = MAX_PORT, message = "Порт сервера IMAP не может быть больше 65535!")
    private int port;

    /**
     * Папка, в которую приходят отчёты.
     */
    @NotBlank(message = "Должна быть задана папка с отчётами о доставке!")
    private String folder;

    /**
     * Пауза между чтениями ящика.
     */
    @NotNull(message = "Должен быть задан интервал чтения отчётов о доставке!")
    @DurationMin(millis = 1, message = "Интервал чтения отчётов о доставке должен быть больше нуля!")
    private Duration pollInterval;

    /**
     * Сколько писем ящика читается за раз; столько же доставок проверяется на инцидент.
     */
    @Min(value = 1, message = "Размер пачки отчётов о доставке должен быть больше нуля!")
    private int batchSize;

    /**
     * Сколько ждать отчёта о важном письме, прежде чем завести инцидент.
     */
    @NotNull(message = "Должен быть задан срок ожидания отчёта о важном письме!")
    @DurationMin(millis = 1, message = "Срок ожидания отчёта о важном письме должен быть больше нуля!")
    private Duration importantTimeout;
}
