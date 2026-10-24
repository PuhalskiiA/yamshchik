package ru.yamshchik.yamshchik.domain;

import java.util.Objects;


/**
 * Отчёт почтового сервера о доставке письма одному получателю.
 *
 * @param attempt   номер попытки отправки, к которой относится отчёт
 * @param recipient адрес получателя в виде {@link Delivery#recipientKey(String)}
 * @param status    расширенный статус (RFC 3463), например {@code 5.1.1}
 */
public record DeliveryReport(
        EmailId emailId,
        int attempt,
        String recipient,
        ReportAction action,
        String status,
        String diagnostic
) {

    private static final String TEMPORARY_STATUS_CLASS = "4";

    public DeliveryReport {
        Objects.requireNonNull(emailId, "emailId");
        Objects.requireNonNull(recipient, "recipient");
        Objects.requireNonNull(action, "action");
    }

    /**
     * Отказ по временной причине: сервер исчерпал свои попытки, но повторная отправка может пройти.
     */
    public boolean temporaryFailure() {
        return action == ReportAction.FAILED && status != null && status.startsWith(TEMPORARY_STATUS_CLASS);
    }
}
