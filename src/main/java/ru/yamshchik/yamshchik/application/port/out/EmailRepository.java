package ru.yamshchik.yamshchik.application.port.out;

import ru.yamshchik.yamshchik.domain.Email;
import ru.yamshchik.yamshchik.domain.EmailId;
import ru.yamshchik.yamshchik.domain.EmailState;
import ru.yamshchik.yamshchik.domain.EmailStatus;

import java.util.Optional;


/**
 * Хранилище писем.
 */
public interface EmailRepository {

    void save(Email email);

    /**
     * @return письмо целиком: состояние, содержимое и вложения
     */
    Optional<Email> find(EmailId id);

    Optional<EmailState> findState(EmailId id);

    long countByStatus(EmailStatus status);

    void updateState(EmailState state);

    /**
     * Записывает итог попытки отправки. Итог не записывается, если аренда истекла и письмо
     * тем временем взял в отправку другой обработчик.
     *
     * @return false, если итог устарел и отброшен
     */
    boolean saveAttemptResult(EmailState result);
}
