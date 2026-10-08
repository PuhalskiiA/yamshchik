package ru.yamshchik.yamshchik.config.properties;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.springframework.util.unit.DataSize;

import java.util.ArrayList;
import java.util.List;


@Getter
@Setter
@NoArgsConstructor
@ToString
public class IntakeProperties {

    /**
     * Наибольший размер письма после кодирования — предел почтового сервера, через который идёт отправка.
     */
    @NotNull(message = "Должен быть задан наибольший размер письма!")
    private DataSize maxMessageSize;

    @Min(value = 1, message = "Наибольшее число получателей письма должно быть больше нуля!")
    private int maxRecipients;

    @Min(value = 0, message = "Наибольшее число вложений письма не может быть отрицательным!")
    private int maxAttachments;

    /**
     * Домены, с адресов которых разрешено отправлять. Пустой список — без ограничений.
     */
    @NotNull(message = "Список разрешённых доменов отправителя не может отсутствовать!")
    private List<@NotBlank(message = "Домен отправителя не может быть пустым!") String> allowedSenderDomains
            = new ArrayList<>();
}
