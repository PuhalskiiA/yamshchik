package ru.yamshchik.yamshchik.domain;

import ru.yamshchik.yamshchik.config.exception.type.ServiceValidationException;


/**
 * Проверки значений, попадающих в заголовки письма.
 */
final class HeaderRules {

    private HeaderRules() {
    }

    // Перевод строки в значении позволил бы дописать в письмо произвольные заголовки
    static void requireSingleLine(String field, String value) {
        if (value != null && (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0)) {
            throw new ServiceValidationException("Line breaks are not allowed in " + field);
        }
    }
}
