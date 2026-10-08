package ru.yamshchik.yamshchik.config.exception.type;

/**
 * Ошибка во входных данных клиента, в том числе нарушение инвариантов модели письма.
 */
public class ServiceValidationException extends RuntimeException {

    public ServiceValidationException(String message) {
        super(message);
    }
}
