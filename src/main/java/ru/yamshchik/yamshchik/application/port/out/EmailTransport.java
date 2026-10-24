package ru.yamshchik.yamshchik.application.port.out;

import ru.yamshchik.yamshchik.config.exception.type.EmailTransportException;
import ru.yamshchik.yamshchik.config.exception.type.ServiceValidationException;
import ru.yamshchik.yamshchik.domain.Email;

import java.util.List;


/**
 * Передача письма почтовому серверу.
 */
public interface EmailTransport {

    /**
     * Передаёт письмо серверу для указанных получателей. Заголовки письма от выбора получателей не зависят:
     * при повторной отправке части получателей в них остаются все адресаты.
     *
     * @param recipients адреса, по которым сервер доставляет письмо
     * @throws EmailTransportException если письмо не удалось собрать или передать серверу
     */
    void send(Email email, List<String> recipients);

    /**
     * Проверяет, что письмо собирается в том же виде, в каком будет отправлено. Содержимое вложений не читается.
     *
     * @throws ServiceValidationException если письмо собрать нельзя
     */
    void verify(Email email);
}
