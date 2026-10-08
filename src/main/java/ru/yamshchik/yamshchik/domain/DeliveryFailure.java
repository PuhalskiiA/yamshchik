package ru.yamshchik.yamshchik.domain;


/**
 * Причина неудачной попытки отправки.
 *
 * @param temporary      повторная попытка может пройти: отказ сервера 4xx, сбой соединения, недоступное хранилище
 * @param replyCode      код ответа почтового сервера, если отказал именно он
 * @param enhancedStatus расширенный статус из ответа сервера (RFC 3463), если сервер его сообщил
 */
public record DeliveryFailure(boolean temporary, Integer replyCode, String enhancedStatus, String message) {

    public static DeliveryFailure permanent(String message) {
        return new DeliveryFailure(false, null, null, message);
    }
}
