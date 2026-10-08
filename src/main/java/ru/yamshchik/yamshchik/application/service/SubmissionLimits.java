package ru.yamshchik.yamshchik.application.service;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;


/**
 * Ограничения на принимаемые письма.
 *
 * @param maxMessageSizeBytes  наибольший размер письма после кодирования
 * @param allowedSenderDomains домены, с которых разрешено отправлять; пусто — без ограничений
 */
public record SubmissionLimits(
        long maxMessageSizeBytes,
        int maxRecipients,
        int maxAttachments,
        Set<String> allowedSenderDomains
) {

    public SubmissionLimits {
        allowedSenderDomains = allowedSenderDomains.stream()
                .map(domain -> domain.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean allowsSenderDomain(String domain) {
        return allowedSenderDomains.isEmpty() || allowedSenderDomains.contains(domain.toLowerCase(Locale.ROOT));
    }
}
