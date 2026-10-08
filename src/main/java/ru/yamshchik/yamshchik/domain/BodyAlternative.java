package ru.yamshchik.yamshchik.domain;

import ru.yamshchik.yamshchik.config.exception.type.ServiceValidationException;


/**
 * Один из равнозначных вариантов тела письма (часть multipart/alternative).
 * Новые виды содержимого, например приглашение в календарь, добавляются как ещё один вариант.
 */
public record BodyAlternative(String mediaType, String content) {

    public static final String TEXT_PLAIN = "text/plain";

    public static final String TEXT_HTML = "text/html";

    private static final String TEXT_TYPE_PREFIX = "text/";

    public BodyAlternative {
        if (mediaType == null || !mediaType.startsWith(TEXT_TYPE_PREFIX)
                || mediaType.length() == TEXT_TYPE_PREFIX.length()) {
            throw new ServiceValidationException("Body media type must be text/*: " + mediaType);
        }
        HeaderRules.requireSingleLine("body media type", mediaType);
        if (content == null) {
            throw new ServiceValidationException("Body content is required");
        }
    }

    public static BodyAlternative plainText(String content) {
        return new BodyAlternative(TEXT_PLAIN, content);
    }

    public static BodyAlternative html(String content) {
        return new BodyAlternative(TEXT_HTML, content);
    }
}
