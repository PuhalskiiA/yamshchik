package ru.yamshchik.yamshchik.config.properties;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;


@Component
@ConfigurationProperties(prefix = "yamshchik", ignoreUnknownFields = false)
@Validated
@Getter
@Setter
@NoArgsConstructor
public class YamshchikProperties {

    /**
     * Настройки обработчика очереди отправки.
     */
    @NotNull(message = "Обязательно должны быть указаны параметры обработчика очереди отправки!")
    @Valid
    private DispatchProperties dispatch;

    /**
     * Отправитель всех писем.
     */
    @NotNull(message = "Обязательно должен быть указан отправитель писем!")
    @Valid
    private SenderProperties sender;

    /**
     * Ограничения на принимаемые письма.
     */
    @NotNull(message = "Обязательно должны быть указаны ограничения на принимаемые письма!")
    @Valid
    private IntakeProperties intake;

    /**
     * Настройки хранилища вложений.
     */
    @NotNull(message = "Обязательно должны быть указаны параметры хранилища вложений!")
    @Valid
    private StorageProperties storage;

    /**
     * Чтение отчётов о доставке.
     */
    @NotNull(message = "Обязательно должны быть указаны параметры чтения отчётов о доставке!")
    @Valid
    private ReportProperties reports;

    /**
     * Публикация событий о доставке.
     */
    @NotNull(message = "Обязательно должны быть указаны параметры публикации событий о доставке!")
    @Valid
    private EventProperties events;
}
