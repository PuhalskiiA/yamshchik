package ru.yamshchik.yamshchik.adapter.out.events;

import java.time.Instant;
import java.util.UUID;


/**
 * Сообщение в Kafka об окончательном исходе доставки письма одному получателю.
 *
 * @param eventId    по нему потребитель отбрасывает повторно опубликованные события
 * @param batchId    общий идентификатор писем, принятых одним запросом
 * @param outcome    {@code DELIVERED} или {@code UNDELIVERED}
 * @param action     действие из отчёта о доставке: {@code DELIVERED}, {@code RELAYED}, {@code EXPANDED}
 *                   или {@code FAILED}; пусто, если письмо не удалось отправить и отчёта нет
 * @param status     расширенный статус из отчёта, например {@code 5.1.1}
 * @param diagnostic ответ почтового сервера
 */
public record DeliveryEventMessage(
        UUID eventId,
        UUID batchId,
        UUID emailId,
        String recipient,
        String outcome,
        String action,
        String status,
        String diagnostic,
        Instant occurredAt
) {
}
