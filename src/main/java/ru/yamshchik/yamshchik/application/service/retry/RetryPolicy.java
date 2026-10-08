package ru.yamshchik.yamshchik.application.service.retry;

import ru.yamshchik.yamshchik.domain.DeliveryFailure;

import java.time.Instant;
import java.util.Optional;


/**
 * Решает, повторять ли отправку после неудачной попытки и когда.
 */
public interface RetryPolicy {

    /**
     * @param attempts сколько попыток уже сделано, включая только что неудавшуюся
     * @return момент следующей попытки или пусто, если письмо больше не отправляется
     */
    Optional<Instant> nextAttemptAt(int attempts, DeliveryFailure failure, Instant now);
}
