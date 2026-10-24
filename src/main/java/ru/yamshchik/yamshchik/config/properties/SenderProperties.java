package ru.yamshchik.yamshchik.config.properties;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;


@Getter
@Setter
@NoArgsConstructor
@ToString
public class SenderProperties {

    /**
     * Адрес учётной записи сервиса: от него уходят все письма, на него же приходят отчёты о доставке.
     */
    @NotBlank(message = "Должен быть задан адрес отправителя!")
    @Email(message = "Адрес отправителя должен быть адресом электронной почты!")
    private String address;

    /**
     * Отображаемое имя отправителя; может отсутствовать.
     */
    private String name;
}
