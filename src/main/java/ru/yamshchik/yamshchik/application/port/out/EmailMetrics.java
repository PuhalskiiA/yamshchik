package ru.yamshchik.yamshchik.application.port.out;

import ru.yamshchik.yamshchik.domain.DeliveryFailure;
import ru.yamshchik.yamshchik.domain.EmailState;
import ru.yamshchik.yamshchik.domain.ReportAction;
import ru.yamshchik.yamshchik.domain.TrafficClass;

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
    void attemptCompleted(EmailState result, TrafficClass trafficClass, DeliveryFailure failure, Duration transportTime);

    /**
     * Применён отчёт о доставке.
     *
     * @param sinceSent сколько прошло от приёма письма сервером до отчёта
     */
    void reportApplied(ReportAction action, TrafficClass trafficClass, Duration sinceSent);

    /**
     * Заведён инцидент: отчёт о важном письме не пришёл вовремя.
     */
    void incident();

    /**
     * Уборка хранилища удалила содержимое вложения.
     *
     * @param forced удалено вложение недавно завершённого письма, раньше срока хранения: места не хватило
     */
    void attachmentDeleted(boolean forced, long bytes);
}
