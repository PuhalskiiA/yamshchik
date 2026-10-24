package ru.yamshchik.yamshchik.adapter.out.persistence;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.yamshchik.yamshchik.application.port.out.DispatchQueue;
import ru.yamshchik.yamshchik.config.properties.DispatchProperties;
import ru.yamshchik.yamshchik.config.properties.YamshchikProperties;
import ru.yamshchik.yamshchik.domain.Email;
import ru.yamshchik.yamshchik.domain.EmailId;
import ru.yamshchik.yamshchik.domain.EmailState;
import ru.yamshchik.yamshchik.domain.EmailStatus;
import ru.yamshchik.yamshchik.domain.TrafficClass;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;


/**
 * Очередь в PostgreSQL: очередью служит сама таблица писем, поэтому она переживает рестарт
 * и доступна любому экземпляру сервиса. Класс трафика хранится в письме.
 * <p>
 * Письмо, аренда которого истекла посреди отправки, второй раз сразу не отдаётся: сервер мог успеть его принять.
 * Оно откладывается на время ожидания отчёта о прерванной попытке, и если отчёт придёт, повтора не будет.
 */
@Component
@ConditionalOnProperty(name = DispatchProperties.QUEUE_PROPERTY, havingValue = DispatchProperties.QUEUE_POSTGRES)
@Slf4j
public class JpaDispatchQueue implements DispatchQueue {

    private final EmailEntityRepository entityRepository;

    private final EmailEntityMapper mapper;

    private final Duration interruptedWait;

    public JpaDispatchQueue(EmailEntityRepository entityRepository, EmailEntityMapper mapper,
                            YamshchikProperties properties) {
        this.entityRepository = entityRepository;
        this.mapper = mapper;
        this.interruptedWait = properties.getDispatch().getInterruptedWait();
    }

    @Override
    public void enqueue(EmailId id, TrafficClass trafficClass, Instant availableAt) {
        // Сохранённое состояние письма уже ставит его в очередь: запись состояния и постановка — одна транзакция
    }

    @Override
    @Transactional
    public Optional<Email> claimNext(TrafficClass trafficClass, Instant now, Duration lease) {
        Optional<EmailEntity> next = entityRepository.findNextAvailableForUpdate(now, trafficClass.name());
        while (next.isPresent()) {
            EmailEntity entity = next.get();
            EmailState state = mapper.toState(entity);
            if (state.status() != EmailStatus.SENDING) {
                mapper.applyState(entity, state.startSending(now, lease));
                return Optional.of(mapper.toEmail(entity));
            }
            EmailState interrupted = state.interrupted(now, now.plus(interruptedWait));
            log.warn("Email {}: attempt {} was interrupted, sending again after {} unless a delivery report comes",
                     state.id(), state.attempts(), interrupted.availableAt());
            mapper.applyState(entity, interrupted);
            // Изменение должно попасть в БД до следующего поиска, иначе то же письмо найдётся снова
            entityRepository.flush();
            next = entityRepository.findNextAvailableForUpdate(now, trafficClass.name());
        }
        return Optional.empty();
    }

    @Override
    @Transactional
    public boolean extendLease(EmailState sending, Instant leaseUntil) {
        return entityRepository.extendLease(sending.id().value(), EmailStatus.SENDING, sending.attempts(),
                                            leaseUntil) > 0;
    }
}
