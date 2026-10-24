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

    private static final String INTERRUPTED_ERROR = "Attempt %d was interrupted, waiting for its delivery report";

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
     * Берёт письмо в отправку на срок аренды. Письмо, отправка которого прервалась, сначала проходит
     * через {@link #interrupted}, напрямую в отправку оно не берётся.
     */
    public EmailState startSending(Instant now, Duration lease) {
        boolean waiting = status == EmailStatus.ACCEPTED || status == EmailStatus.DEFERRED;
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

    /**
     * Отправка прервалась: аренда истекла, а итог не записан — обработчик, скорее всего, упал. Сервер мог
     * успеть принять письмо, поэтому сразу оно не отправляется: сначала ждёт отчёта о прерванной попытке.
     *
     * @param waitUntil до какого момента ждать отчёта; после — письмо уходит снова
     */
    public EmailState interrupted(Instant now, Instant waitUntil) {
        if (status != EmailStatus.SENDING || availableAt == null || availableAt.isAfter(now)) {
            throw transitionNotAllowed(EmailStatus.DEFERRED);
        }
        Objects.requireNonNull(waitUntil, "waitUntil");
        return new EmailState(id, EmailStatus.DEFERRED, attempts, INTERRUPTED_ERROR.formatted(attempts), createdAt,
                              now, null, waitUntil);
    }

    /**
     * Сервер принял письмо, но потом сообщил об отказе по временной причине: письмо уйдёт ещё раз
     * тем получателям, по которым пришёл отказ.
     */
    public EmailState deferredByReport(String error, Instant now, Instant nextAttemptAt) {
        if (status != EmailStatus.SENT) {
            throw transitionNotAllowed(EmailStatus.DEFERRED);
        }
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
