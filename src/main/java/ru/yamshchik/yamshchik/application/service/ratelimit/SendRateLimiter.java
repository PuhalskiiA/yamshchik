package ru.yamshchik.yamshchik.application.service.ratelimit;

import ru.yamshchik.yamshchik.domain.TrafficClass;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;


/**
 * Ограничитель темпа отправки, общий для всех обработчиков экземпляра. Реализация выбирается
 * настройкой: так на одной кодовой базе сравниваются стратегии управления темпом.
 */
public interface SendRateLimiter {

    /**
     * Берёт разрешение на отправку одного письма класса.
     *
     * @param urgentIdle срочных писем, готовых к отправке, нет — массовые могут занять их долю
     * @return разрешение или пусто, если темп сейчас отправки не допускает
     */
    Optional<Permit> tryAcquire(TrafficClass trafficClass, boolean urgentIdle, Instant now);

    /**
     * Возвращает неиспользованное разрешение: письма этого класса в очереди не оказалось.
     */
    void release(Permit permit);

    /**
     * Сообщает исход отправки.
     */
    void onResult(SendSignal signal, Instant now);

    /**
     * @return текущий темп, писем в секунду; {@link Double#POSITIVE_INFINITY} — без ограничения
     */
    double rate();

    /**
     * @return через сколько темп позволит взять следующее разрешение
     */
    Duration timeUntilPermit(Instant now);

    /**
     * Разрешение на отправку одного письма.
     *
     * @param share {@code BULK} — разрешение взято и из доли массовых писем, {@code URGENT} — только из общего темпа
     */
    record Permit(TrafficClass share) {
    }
}
