package ru.yamshchik.yamshchik.adapter.out.queue;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import ru.yamshchik.yamshchik.application.port.out.DispatchQueue;
import ru.yamshchik.yamshchik.application.port.out.EmailRepository;
import ru.yamshchik.yamshchik.config.properties.DispatchProperties;
import ru.yamshchik.yamshchik.domain.Email;
import ru.yamshchik.yamshchik.domain.EmailId;
import ru.yamshchik.yamshchik.domain.EmailState;
import ru.yamshchik.yamshchik.domain.TrafficClass;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;


/**
 * Очередь в памяти процесса — исходный вариант для сравнения. При остановке процесса очередь теряется:
 * письма, которые в ней ждали или были в отправке, остаются в хранилище и никогда не отправляются.
 */
@Component
@ConditionalOnProperty(name = DispatchProperties.QUEUE_PROPERTY, havingValue = DispatchProperties.QUEUE_MEMORY)
@RequiredArgsConstructor
public class InMemoryDispatchQueue implements DispatchQueue {

    private final Map<TrafficClass, PriorityQueue<Entry>> queues = new EnumMap<>(TrafficClass.class);

    private final EmailRepository emailRepository;

    private long nextSequence;

    @Override
    public synchronized void enqueue(EmailId id, TrafficClass trafficClass, Instant availableAt) {
        queue(trafficClass).add(new Entry(id, availableAt, nextSequence++));
    }

    @Override
    public synchronized Optional<Email> claimNext(TrafficClass trafficClass, Instant now, Duration lease) {
        PriorityQueue<Entry> queue = queue(trafficClass);
        while (!queue.isEmpty() && !queue.peek().availableAt().isAfter(now)) {
            EmailId id = queue.poll().id();
            Optional<Email> claimed = emailRepository.find(id).map(email -> startSending(email, now, lease));
            if (claimed.isPresent()) {
                return claimed;
            }
        }
        return Optional.empty();
    }

    // Брошенное письмо эта очередь второй раз не выдаёт, поэтому продлевать нечего
    @Override
    public boolean extendLease(EmailState sending, Instant leaseUntil) {
        return true;
    }

    private PriorityQueue<Entry> queue(TrafficClass trafficClass) {
        return queues.computeIfAbsent(trafficClass, ignored -> new PriorityQueue<>(
                Comparator.comparing(Entry::availableAt).thenComparingLong(Entry::sequence)));
    }

    private Email startSending(Email email, Instant now, Duration lease) {
        EmailState sending = email.state().startSending(now, lease);
        emailRepository.updateState(sending);
        return new Email(sending, email.message(), email.attachments());
    }

    /**
     * @param sequence порядок постановки: сохраняет очерёдность писем с одинаковым временем
     */
    private record Entry(EmailId id, Instant availableAt, long sequence) {
    }
}
