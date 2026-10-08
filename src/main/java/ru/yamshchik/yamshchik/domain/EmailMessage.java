package ru.yamshchik.yamshchik.domain;

import ru.yamshchik.yamshchik.config.exception.type.ServiceValidationException;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;


/**
 * Содержимое письма без вложений: то, что задаёт отправитель.
 *
 * @param body варианты тела в порядке возрастания предпочтительности
 */
public record EmailMessage(
        Mailbox from,
        Mailbox replyTo,
        Recipients recipients,
        String subject,
        List<BodyAlternative> body,
        Priority priority,
        Map<String, String> headers
) {

    private static final String CUSTOM_HEADER_PREFIX = "x-";

    private static final String RESERVED_HEADER_PREFIX = "x-yamshchik-";

    private static final Pattern HEADER_NAME_PATTERN = Pattern.compile("[A-Za-z0-9-]+");

    public EmailMessage {
        if (from == null || recipients == null || subject == null) {
            throw new ServiceValidationException("Sender, recipients and subject are required");
        }
        HeaderRules.requireSingleLine("subject", subject);
        body = body == null ? List.of() : List.copyOf(body);
        priority = priority == null ? Priority.NORMAL : priority;
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        headers.forEach(EmailMessage::validateHeader);
    }

    private static void validateHeader(String name, String value) {
        String lowerName = name.toLowerCase(Locale.ROOT);
        if (!HEADER_NAME_PATTERN.matcher(name).matches()
                || !lowerName.startsWith(CUSTOM_HEADER_PREFIX)
                || lowerName.startsWith(RESERVED_HEADER_PREFIX)) {
            throw new ServiceValidationException("Header is not allowed: " + name);
        }
        HeaderRules.requireSingleLine("header " + name, value);
    }
}
