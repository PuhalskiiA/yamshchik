package ru.yamshchik.yamshchik.config.exception.type;

import java.util.UUID;


/**
 * Письмо с запрошенным идентификатором не существует.
 */
public class EmailNotFoundException extends RuntimeException {

    public EmailNotFoundException(UUID emailId) {
        super("Email not found: " + emailId);
    }
}
