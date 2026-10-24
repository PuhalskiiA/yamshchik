package ru.yamshchik.yamshchik.application.port.out;

import ru.yamshchik.yamshchik.domain.Delivery;
import ru.yamshchik.yamshchik.domain.DeliveryEvent;
import ru.yamshchik.yamshchik.domain.DeliveryStatus;
import ru.yamshchik.yamshchik.domain.Email;
import ru.yamshchik.yamshchik.domain.EmailId;
import ru.yamshchik.yamshchik.domain.EmailState;
import ru.yamshchik.yamshchik.domain.EmailStatus;
import ru.yamshchik.yamshchik.domain.Priority;
import ru.yamshchik.yamshchik.domain.TrafficClass;

import java.time.Instant;
import java.util.List;
import java.util.Optional;


/**
 * Хранилище писем и доставок их получателям. События о доставке записываются в той же транзакции,
 * что и изменение доставки, и потом публикуются из {@link DeliveryEventOutbox}.
 */
public interface EmailRepository {

    /**
     * Сохраняет письма одного запроса и доставки их получателям: всё или ничего.
     */
    void saveAll(List<Email> emails, List<Delivery> deliveries);

    /**
     * @return письмо целиком: состояние, содержимое и вложения
     */
    Optional<Email> find(EmailId id);

    Optional<EmailState> findState(EmailId id);

    long countByStatus(EmailStatus status);

    /**
     * @return сколько писем класса ждут отправки: приняты или отложены
     */
    long countWaiting(TrafficClass trafficClass);

    long countDeliveriesByStatus(DeliveryStatus status);

    void updateState(EmailState state);

    /**
     * Записывает итог попытки отправки вместе с изменёнными доставками и событиями. Ничего не записывается,
     * если аренда истекла и письмо тем временем взял в отправку другой обработчик.
     *
     * @param deliveries доставки, которые в этой попытке перестали ждать отправки
     * @return false, если итог устарел и отброшен
     */
    boolean saveAttemptResult(EmailState result, List<Delivery> deliveries, List<DeliveryEvent> events);

    /**
     * Возвращает письмо, взятое в отправку, обратно в ожидание без отправки. Ничего не записывается,
     * если письмо тем временем забрал другой обработчик.
     *
     * @return false, если письмо уже не у этого обработчика
     */
    boolean savePostponed(EmailState postponed);

    /**
     * @return доставки письма в порядке получателей
     */
    List<Delivery> findDeliveries(EmailId id);

    Optional<Delivery> findDelivery(EmailId id, String recipient);

    /**
     * @return доставки, которые ждут отчёта с момента раньше {@code sentBefore} и по которым инцидент не заведён
     */
    List<Delivery> findDeliveriesAwaitingReport(Priority priority, Instant sentBefore, int limit);

    /**
     * Записывает итог отчёта о доставке. Доставка до отчёта должна быть в статусе {@code expectedStatus}:
     * {@code SENT} — ждала отчёта в той же попытке, {@code PENDING} — итог прерванной попытки не был записан.
     * Письмо, если оно передано, должно быть в статусе {@code SENT} с тем же числом попыток. Если хоть что-то
     * изменилось параллельно, не записывается ничего.
     *
     * @param email новое состояние письма или null, если оно не меняется
     * @param event событие об исходе доставки или null, если исход не окончательный
     * @return false, если состояние изменилось параллельно и отчёт нужно обработать заново
     */
    boolean saveReportResult(DeliveryStatus expectedStatus, Delivery delivery, EmailState email, DeliveryEvent event);

    /**
     * Отмечает инцидент по доставке, если он ещё не заведён и доставка всё ещё ждёт отчёта.
     *
     * @return false, если инцидент уже заведён или отчёт успел прийти
     */
    boolean saveIncident(Delivery delivery);
}
