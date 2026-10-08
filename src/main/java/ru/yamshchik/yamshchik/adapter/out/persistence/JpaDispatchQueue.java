package ru.yamshchik.yamshchik.adapter.out.persistence;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.yamshchik.yamshchik.application.port.out.DispatchQueue;
import ru.yamshchik.yamshchik.config.properties.DispatchProperties;
import ru.yamshchik.yamshchik.domain.Email;
import ru.yamshchik.yamshchik.domain.EmailId;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;


/**
 * Очередь в PostgreSQL: очередью служит сама таблица писем, поэтому она переживает рестарт
 * и доступна любому экземпляру сервиса.
 */
@Component
@ConditionalOnProperty(name = DispatchProperties.QUEUE_PROPERTY, havingValue = DispatchProperties.QUEUE_POSTGRES)
@RequiredArgsConstructor
public class JpaDispatchQueue implements DispatchQueue {

    private final EmailEntityRepository entityRepository;

    private final EmailEntityMapper mapper;

    @Override
    public void enqueue(EmailId id, Instant availableAt) {
        // Сохранённое состояние письма уже ставит его в очередь: запись состояния и постановка — одна транзакция
    }

    @Override
    @Transactional
    public Optional<Email> claimNext(Instant now, Duration lease) {
        return entityRepository.findNextAvailableForUpdate(now)
                .map(entity -> {
                    mapper.applyState(entity, mapper.toState(entity).startSending(now, lease));
                    return mapper.toEmail(entity);
                });
    }
}
