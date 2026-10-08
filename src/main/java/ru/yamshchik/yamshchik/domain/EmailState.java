package ru.yamshchik.yamshchik.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;


/**
 * Состояние письма в очереди отправки. Все переходы статусов описаны только здесь.
 *
 * @param availableAt момент, с которого письмо можно взять в отправку: для принятого — время приёма,
 *                    для отложенного — время следующей попытки, для отправляемого — конец аренды,
 *                    после которого письмо считается брошенным. У письма в конечном статусе не задан
 */
public record EmailState(
        EmailId id,
        EmailStatus status,
        int attempts,
        String lastError,
        Instant createdAt,
        Instant updatedAt,
        Instant sentAt,
        Instant availableAt
) {

    public EmailState {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public static EmailState accepted(EmailId id, Instant now) {
        return new EmailState(id, EmailStatus.ACCEPTED, 0, null, now, now, null, now);
    }

    /**
     * Берёт письмо в отправку на срок аренды. Отправляемое письмо можно взять повторно,
     * только когда аренда предыдущего обработчика истекла.
     */
    public EmailState startSending(Instant now, Duration lease) {
        boolean waiting = status == EmailStatus.ACCEPTED || status == EmailStatus.DEFERRED
                || status == EmailStatus.SENDING;
        if (!waiting || availableAt == null || availableAt.isAfter(now)) {
            throw transitionNotAllowed(EmailStatus.SENDING);
        }
        return new EmailState(id, EmailStatus.SENDING, attempts + 1, lastError, createdAt, now, null,
                              now.plus(lease));
    }

    public EmailState sent(Instant now) {
        requireSending(EmailStatus.SENT);
        return new EmailState(id, EmailStatus.SENT, attempts, null, createdAt, now, now, null);
    }

    public EmailState deferred(String error, Instant now, Instant nextAttemptAt) {
        requireSending(EmailStatus.DEFERRED);
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
        return new EmailState(id, EmailStatus.DEFERRED, attempts, error, createdAt, now, null, nextAttemptAt);
    }

    public EmailState failed(String error, Instant now) {
        requireSending(EmailStatus.FAILED);
        return new EmailState(id, EmailStatus.FAILED, attempts, error, createdAt, now, null, null);
    }

    private void requireSending(EmailStatus target) {
        if (status != EmailStatus.SENDING) {
            throw transitionNotAllowed(target);
        }
    }

    private IllegalStateException transitionNotAllowed(EmailStatus target) {
        return new IllegalStateException(
                "Email %s: transition %s -> %s is not allowed".formatted(id, status, target));
    }
}
