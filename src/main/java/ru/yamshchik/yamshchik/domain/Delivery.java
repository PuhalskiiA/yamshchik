package ru.yamshchik.yamshchik.domain;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;


/**
 * Доставка письма одному получателю. Все переходы статусов доставки описаны только здесь.
 *
 * @param batchId      общий идентификатор писем, принятых одним запросом
 * @param recipient    адрес получателя в виде {@link #recipientKey(String)}
 * @param attempt      номер попытки, в которой письмо последний раз ушло этому получателю; 0 — ещё не уходило
 * @param reportAction действие из последнего отчёта о доставке
 * @param reportStatus расширенный статус из последнего отчёта
 * @param diagnostic   ответ сервера из последнего отчёта или причина отказа при отправке
 * @param sentAt       когда сервер принял письмо для этого получателя в последний раз
 * @param incidentAt   когда по доставке заведён инцидент: отчёт о важном письме не пришёл вовремя
 */
public record Delivery(
        EmailId emailId,
        UUID batchId,
        String recipient,
        Priority priority,
        DeliveryStatus status,
        int attempt,
        ReportAction reportAction,
        String reportStatus,
        String diagnostic,
        Instant updatedAt,
        Instant sentAt,
        Instant incidentAt
) {

    public Delivery {
        Objects.requireNonNull(emailId, "emailId");
        Objects.requireNonNull(batchId, "batchId");
        Objects.requireNonNull(recipient, "recipient");
        Objects.requireNonNull(priority, "priority");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public static Delivery pending(EmailId emailId, UUID batchId, String address, Priority priority, Instant now) {
        return new Delivery(emailId, batchId, recipientKey(address), priority, DeliveryStatus.PENDING, 0,
                            null, null, null, now, null, null);
    }

    /**
     * Адрес в том виде, в каком по нему сопоставляются отчёты: регистр в адресе роли не играет.
     */
    public static String recipientKey(String address) {
        return address.trim().toLowerCase(Locale.ROOT);
    }

    public boolean isFinal() {
        return status == DeliveryStatus.DELIVERED || status == DeliveryStatus.UNDELIVERED;
    }

    /**
     * Сервер принял письмо для этого получателя.
     */
    public Delivery sent(int attempt, Instant now) {
        require(DeliveryStatus.PENDING, DeliveryStatus.SENT);
        // Отчёт о прошлой попытке к новой не относится
        return new Delivery(emailId, batchId, recipient, priority, DeliveryStatus.SENT, attempt,
                            null, null, null, now, now, null);
    }

    /**
     * Пришёл отчёт о прерванной попытке, итог которой не записан: значит, сервер письмо в ней принял.
     * Доставка становится такой, какой была бы, успей обработчик записать итог.
     */
    public Delivery recovered(int interruptedAttempt, Instant now) {
        if (interruptedAttempt <= attempt) {
            throw new IllegalStateException("Delivery %s to %s: attempt %d is not newer than %d"
                                                    .formatted(emailId, recipient, interruptedAttempt, attempt));
        }
        return sent(interruptedAttempt, now);
    }

    /**
     * Отправка не удалась окончательно, отчёта о доставке не будет.
     */
    public Delivery undelivered(String error, Instant now) {
        require(DeliveryStatus.PENDING, DeliveryStatus.UNDELIVERED);
        return new Delivery(emailId, batchId, recipient, priority, DeliveryStatus.UNDELIVERED, attempt,
                            null, null, error, now, sentAt, incidentAt);
    }

    /**
     * Отчёт о доставке или о передаче письма дальше.
     */
    public Delivery delivered(DeliveryReport report, Instant now) {
        requireReport(report, DeliveryStatus.DELIVERED);
        return withReport(DeliveryStatus.DELIVERED, report, now);
    }

    /**
     * Отчёт о задержке: сервер продолжает доставлять сам, статус не меняется.
     */
    public Delivery delayed(DeliveryReport report, Instant now) {
        requireReport(report, DeliveryStatus.SENT);
        return withReport(DeliveryStatus.SENT, report, now);
    }

    /**
     * Отчёт об окончательном отказе, после которого письмо этому получателю больше не отправляется.
     */
    public Delivery undelivered(DeliveryReport report, Instant now) {
        requireReport(report, DeliveryStatus.UNDELIVERED);
        return withReport(DeliveryStatus.UNDELIVERED, report, now);
    }

    /**
     * Отчёт об отказе по временной причине: письмо этому получателю уйдёт ещё раз.
     */
    public Delivery retry(DeliveryReport report, Instant now) {
        requireReport(report, DeliveryStatus.PENDING);
        return withReport(DeliveryStatus.PENDING, report, now);
    }

    /**
     * Отчёт о важном письме не пришёл вовремя.
     */
    public Delivery incident(Instant now) {
        require(DeliveryStatus.SENT, status);
        if (incidentAt != null) {
            throw new IllegalStateException("Delivery %s to %s: incident is already raised".formatted(emailId, recipient));
        }
        return new Delivery(emailId, batchId, recipient, priority, status, attempt,
                            reportAction, reportStatus, diagnostic, now, sentAt, now);
    }

    private Delivery withReport(DeliveryStatus target, DeliveryReport report, Instant now) {
        return new Delivery(emailId, batchId, recipient, priority, target, attempt,
                            report.action(), report.status(), report.diagnostic(), now, sentAt, incidentAt);
    }

    // Отчёт применяется только к доставке, которая ждёт его, и только к той попытке, о которой он написан
    private void requireReport(DeliveryReport report, DeliveryStatus target) {
        require(DeliveryStatus.SENT, target);
        if (!report.emailId().equals(emailId) || !report.recipient().equals(recipient) || report.attempt() != attempt) {
            throw new IllegalStateException("Delivery %s to %s, attempt %d: report %s is for another delivery"
                                                    .formatted(emailId, recipient, attempt, report));
        }
    }

    private void require(DeliveryStatus expected, DeliveryStatus target) {
        if (status != expected) {
            throw new IllegalStateException("Delivery %s to %s: transition %s -> %s is not allowed"
                                                    .formatted(emailId, recipient, status, target));
        }
    }
}
