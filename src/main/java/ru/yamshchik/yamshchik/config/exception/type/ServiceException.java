package ru.yamshchik.yamshchik.config.exception.type;

/**
 * Неожиданная нештатная ошибка сервиса.
 */
public class ServiceException extends RuntimeException {

    public ServiceException(String message) {
        super(message);
    }

    public ServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
