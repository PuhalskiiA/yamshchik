package ru.yamshchik.yamshchik.config.exception.type;

import lombok.Getter;
import ru.yamshchik.yamshchik.domain.DeliveryFailure;


/**
 * Письмо не удалось собрать или передать почтовому серверу.
 */
@Getter
public class EmailTransportException extends RuntimeException {

    private final DeliveryFailure failure;

    public EmailTransportException(DeliveryFailure failure, Throwable cause) {
        super(failure.message(), cause);
        this.failure = failure;
    }
}
