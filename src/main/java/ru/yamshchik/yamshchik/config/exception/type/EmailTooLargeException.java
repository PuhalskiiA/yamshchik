package ru.yamshchik.yamshchik.config.exception.type;


/**
 * Письмо после кодирования превышает допустимый размер.
 */
public class EmailTooLargeException extends ServiceValidationException {

    public EmailTooLargeException(String message) {
        super(message);
    }
}
