package ru.yamshchik.yamshchik.application.port.out;

import ru.yamshchik.yamshchik.application.service.ratelimit.SendSignal;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;


/**
 * Темп отправки на каждый сервер получателя, общий для всех экземпляров сервиса.
 */
public interface DestinationRateLimiter {

    /**
     * @return можно ли сейчас брать разрешения; если нет, письма в отправку не захватываются
     */
    boolean available();

    /**
     * Берёт по разрешению на каждый домен разом: либо на все, либо ни на один.
     *
     * @return пусто — разрешения взяты; иначе — через сколько попробовать снова
     * @throws RuntimeException если общий счётчик недоступен
     */
    Optional<Duration> tryAcquire(Set<String> domains);

    /**
     * Сообщает, что сервер ответил на отправку письма на эти домены: по ответу темп подстраивается.
     */
    void onResult(Set<String> domains, SendSignal signal);
}
