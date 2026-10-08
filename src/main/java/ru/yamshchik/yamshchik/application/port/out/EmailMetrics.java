package ru.yamshchik.yamshchik.application.port.out;

import ru.yamshchik.yamshchik.domain.DeliveryFailure;
import ru.yamshchik.yamshchik.domain.EmailState;

import java.time.Duration;


/**
 * Показатели работы сервиса: по ним сравниваются варианты очереди, повторов и темпа отправки.
 */
public interface EmailMetrics {

    void accepted();

    /**
     * @param result        состояние письма после попытки
     * @param failure       причина отказа или null, если почтовый сервер принял письмо
     * @param transportTime сколько длилась передача письма серверу, включая сборку
     */
    void attemptCompleted(EmailState result, DeliveryFailure failure, Duration transportTime);
}
