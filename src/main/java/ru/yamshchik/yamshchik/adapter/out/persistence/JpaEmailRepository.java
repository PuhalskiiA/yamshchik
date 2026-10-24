package ru.yamshchik.yamshchik.adapter.out.persistence;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import ru.yamshchik.yamshchik.application.port.out.EmailRepository;
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
import java.util.UUID;


@Repository
@RequiredArgsConstructor
public class JpaEmailRepository implements EmailRepository {

    private static final List<EmailStatus> WAITING_STATUSES = List.of(EmailStatus.ACCEPTED, EmailStatus.DEFERRED);

    private final EmailEntityRepository entityRepository;

    private final DeliveryEntityRepository deliveryRepository;

    private final DeliveryEventEntityRepository eventRepository;

    private final EmailEntityMapper mapper;

    @Override
    @Transactional
    public void saveAll(List<Email> emails, List<Delivery> deliveries) {
        entityRepository.saveAll(emails.stream().map(mapper::toEntity).toList());
        deliveryRepository.saveAll(deliveries.stream().map(mapper::toDeliveryEntity).toList());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Email> find(EmailId id) {
        return entityRepository.findById(id.value()).map(mapper::toEmail);
    }

    @Override
    public Optional<EmailState> findState(EmailId id) {
        return entityRepository.findStateById(id.value()).map(mapper::toState);
    }

    @Override
    public long countByStatus(EmailStatus status) {
        return entityRepository.countByStatus(status);
    }

    @Override
    public long countWaiting(TrafficClass trafficClass) {
        return entityRepository.countByTrafficClassAndStatusIn(trafficClass, WAITING_STATUSES);
    }

    @Override
    public long countDeliveriesByStatus(DeliveryStatus status) {
        return deliveryRepository.countByStatus(status);
    }

    @Override
    @Transactional
    public void updateState(EmailState state) {
        entityRepository.updateState(state.id().value(),
                                     state.status(),
                                     state.attempts(),
                                     state.lastError(),
                                     state.updatedAt(),
                                     state.sentAt(),
                                     state.availableAt());
    }

    @Override
    @Transactional
    public boolean saveAttemptResult(EmailState result, List<Delivery> deliveries, List<DeliveryEvent> events) {
        if (!updateIfUnchanged(result, EmailStatus.SENDING)) {
            return false;
        }
        // Письмо закреплено за этим обработчиком, поэтому доставки, ждущие отправки, параллельно не меняются
        for (Delivery delivery : deliveries) {
            if (!updateIfUnchanged(delivery, DeliveryStatus.PENDING)) {
                throw new IllegalStateException(
                        "Delivery %s to %s changed while the email was being sent".formatted(delivery.emailId(),
                                                                                               delivery.recipient()));
            }
        }
        eventRepository.saveAll(events.stream().map(mapper::toEventEntity).toList());
        return true;
    }

    // Отложенное письмо потеряло одну попытку, поэтому обработчик узнаётся по номеру попытки на единицу больше
    @Override
    @Transactional
    public boolean savePostponed(EmailState postponed) {
        return entityRepository.postpone(postponed.id().value(), EmailStatus.SENDING, postponed.attempts() + 1,
                                         postponed.status(), postponed.attempts(), postponed.updatedAt(),
                                         postponed.availableAt()) > 0;
    }

    @Override
    public List<Delivery> findDeliveries(EmailId id) {
        return deliveryRepository.findByEmailIdOrderById(id.value()).stream().map(mapper::toDelivery).toList();
    }

    @Override
    public Optional<Delivery> findDelivery(EmailId id, String recipient) {
        return deliveryRepository.findByEmailIdAndRecipient(id.value(), recipient).map(mapper::toDelivery);
    }

    @Override
    public List<Delivery> findDeliveriesAwaitingReport(Priority priority, Instant sentBefore, int limit) {
        return deliveryRepository
                .findByStatusAndPriorityAndSentAtLessThanEqualAndIncidentAtIsNullOrderBySentAt(
                        DeliveryStatus.SENT, priority, sentBefore, Limit.of(limit))
                .stream()
                .map(mapper::toDelivery)
                .toList();
    }

    @Override
    @Transactional
    public boolean saveReportResult(
            DeliveryStatus expectedStatus,
            Delivery delivery,
            EmailState email,
            DeliveryEvent event
    ) {
        if (!updateIfUnchanged(delivery, expectedStatus)) {
            return false;
        }
        if (email != null && !updateIfUnchanged(email, EmailStatus.SENT)) {
            // Доставка уже изменена в этой транзакции — откатываем, отчёт будет обработан заново
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return false;
        }
        if (event != null) {
            eventRepository.save(mapper.toEventEntity(event));
        }
        return true;
    }

    @Override
    @Transactional
    public boolean saveIncident(Delivery delivery) {
        return deliveryRepository.markIncident(delivery.emailId().value(),
                                               delivery.recipient(),
                                               DeliveryStatus.SENT,
                                               delivery.attempt(),
                                               delivery.incidentAt()) > 0;
    }

    // Число попыток в условии отсекает итог обработчика, у которого письмо уже забрали
    private boolean updateIfUnchanged(EmailState state, EmailStatus expectedStatus) {
        return entityRepository.updateStateOfAttempt(state.id().value(),
                                                     expectedStatus,
                                                     state.attempts(),
                                                     state.status(),
                                                     state.lastError(),
                                                     state.updatedAt(),
                                                     state.sentAt(),
                                                     state.availableAt()) > 0;
    }

    // Отчёт относится к одной попытке, поэтому доставка, ждущая отчёта, сверяется и по номеру попытки
    private boolean updateIfUnchanged(Delivery delivery, DeliveryStatus expectedStatus) {
        UUID emailId = delivery.emailId().value();
        int updated = expectedStatus == DeliveryStatus.PENDING
                ? deliveryRepository.updateIfStatus(emailId, delivery.recipient(), expectedStatus,
                                                    delivery.status(), delivery.attempt(), delivery.reportAction(),
                                                    delivery.reportStatus(), delivery.diagnostic(),
                                                    delivery.updatedAt(), delivery.sentAt(), delivery.incidentAt())
                : deliveryRepository.updateIfUnchanged(emailId, delivery.recipient(), expectedStatus,
                                                       delivery.attempt(), delivery.status(), delivery.attempt(),
                                                       delivery.reportAction(), delivery.reportStatus(),
                                                       delivery.diagnostic(), delivery.updatedAt(),
                                                       delivery.sentAt(), delivery.incidentAt());
        return updated > 0;
    }
}
