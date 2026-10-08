package ru.yamshchik.yamshchik.application.port.out;

import ru.yamshchik.yamshchik.domain.Email;
import ru.yamshchik.yamshchik.domain.EmailId;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;


/**
 * Очередь отправки. От реализации зависит, переживает ли очередь рестарт и какие гарантии доставки она даёт.
 */
public interface DispatchQueue {

    /**
     * Ставит в очередь письмо, состояние которого уже сохранено: только что принятое или отложенное.
     *
     * @param availableAt момент, раньше которого письмо в отправку не отдаётся
     */
    void enqueue(EmailId id, Instant availableAt);

    /**
     * Атомарно забирает письмо, которое ждёт дольше всех, и переводит его в отправку на срок аренды.
     * Одно письмо не достаётся двум вызывающим одновременно, пока аренда не истекла.
     *
     * @return письмо уже в новом состоянии или пусто, если готовых к отправке писем нет
     */
    Optional<Email> claimNext(Instant now, Duration lease);
}
