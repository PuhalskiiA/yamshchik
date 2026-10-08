package ru.yamshchik.yamshchik.application.port.out;

import ru.yamshchik.yamshchik.config.exception.type.EmailTransportException;
import ru.yamshchik.yamshchik.config.exception.type.ServiceValidationException;
import ru.yamshchik.yamshchik.domain.Email;


/**
 * Передача письма почтовому серверу.
 */
public interface EmailTransport {

    /**
     * @throws EmailTransportException если письмо не удалось собрать или передать серверу
     */
    void send(Email email);

    /**
     * Проверяет, что письмо собирается в том же виде, в каком будет отправлено. Содержимое вложений не читается.
     *
     * @throws ServiceValidationException если письмо собрать нельзя
     */
    void verify(Email email);
}
