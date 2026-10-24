package ru.yamshchik.yamshchik.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.yamshchik.yamshchik.application.port.in.DispatchEmailsUseCase;
import ru.yamshchik.yamshchik.application.port.in.DispatchEmailsUseCase.DispatchResult;
import ru.yamshchik.yamshchik.application.port.out.DestinationRateLimiter;
import ru.yamshchik.yamshchik.application.port.out.DispatchQueue;
import ru.yamshchik.yamshchik.application.port.out.EmailMetrics;
import ru.yamshchik.yamshchik.application.port.out.EmailRepository;
import ru.yamshchik.yamshchik.application.port.out.EmailTransport;
import ru.yamshchik.yamshchik.application.service.ratelimit.SendRateLimiter;
import ru.yamshchik.yamshchik.application.service.ratelimit.SendRateLimiter.Permit;
import ru.yamshchik.yamshchik.application.service.ratelimit.SendSignal;
import ru.yamshchik.yamshchik.application.service.retry.RetryPolicy;
import ru.yamshchik.yamshchik.config.exception.type.EmailTransportException;
import ru.yamshchik.yamshchik.config.properties.YamshchikProperties;
import ru.yamshchik.yamshchik.domain.Delivery;
import ru.yamshchik.yamshchik.domain.DeliveryEvent;
import ru.yamshchik.yamshchik.domain.DeliveryFailure;
import ru.yamshchik.yamshchik.domain.DeliveryStatus;
import ru.yamshchik.yamshchik.domain.Email;
import ru.yamshchik.yamshchik.domain.EmailState;
import ru.yamshchik.yamshchik.domain.EmailStatus;
import ru.yamshchik.yamshchik.domain.TrafficClass;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;


@Service
@RequiredArgsConstructor
@Slf4j
public class EmailDispatchService implements DispatchEmailsUseCase {

    private static final String NO_RECIPIENTS_ERROR = "No recipients are waiting for this email";

    private static final List<TrafficClass> DISPATCH_ORDER = List.of(TrafficClass.URGENT, TrafficClass.BULK);

    private static final char ADDRESS_SEPARATOR = '@';

    private final EmailRepository emailRepository;

    private final DispatchQueue dispatchQueue;

    private final EmailTransport emailTransport;

    private final RetryPolicy retryPolicy;

    private final SendRateLimiter rateLimiter;

    private final LeaseKeeper leaseKeeper;

    private final DestinationRateLimiter destinationLimiter;

    private final EmailMetrics metrics;

    private final YamshchikProperties properties;

    private final Clock clock;

    /**
     * Срочные письма берутся первыми; массовые — если на них хватает темпа. Разрешение берётся до захвата письма:
     * захваченное письмо пришлось бы возвращать в очередь, а неиспользованное разрешение просто возвращается.
     */
    @Override
    public DispatchResult dispatchNext() {
        // Без общего счётчика темпа письма не захватываются: иначе их пришлось бы возвращать в очередь
        if (!destinationLimiter.available()) {
            return DispatchResult.rateLimited(properties.getDispatch().getDestinationLimit().getUnavailableWait());
        }
        boolean urgentIdle = false;
        boolean rateLimited = false;
        for (TrafficClass trafficClass : DISPATCH_ORDER) {
            Optional<Permit> permit = rateLimiter.tryAcquire(trafficClass, urgentIdle, clock.instant());
            if (permit.isEmpty()) {
                rateLimited = true;
                continue;
            }
            Optional<Email> claimed = dispatchQueue.claimNext(trafficClass, clock.instant(),
                                                              properties.getDispatch().getLease());
            if (claimed.isPresent()) {
                return dispatch(claimed.get(), trafficClass);
            }
            rateLimiter.release(permit.get());
            urgentIdle |= trafficClass == TrafficClass.URGENT;
        }
        return rateLimited
                ? DispatchResult.rateLimited(rateLimiter.timeUntilPermit(clock.instant()))
                : DispatchResult.IDLE;
    }

    /**
     * @return {@link DispatchResult#DISPATCHED}, если попытка сделана; если письмо отложено по темпу сервера
     *         получателя — через сколько он примет следующее письмо, чтобы обработчик не ждал следующего опроса
     */
    private DispatchResult dispatch(Email email, TrafficClass trafficClass) {
        // Письмо уходит только тем, кто его ещё не получил: при повторе после отчёта об отказе — части получателей
        List<Delivery> deliveries = emailRepository.findDeliveries(email.id());
        List<Delivery> pending = deliveries.stream()
                .filter(delivery -> delivery.status() == DeliveryStatus.PENDING)
                .toList();
        Set<String> domains = domainsOf(pending);
        if (!pending.isEmpty()) {
            Optional<Duration> wait = acquireDestinations(email, trafficClass, domains);
            if (wait.isPresent()) {
                return DispatchResult.rateLimited(wait.get());
            }
        }
        EmailState result;
        if (!pending.isEmpty()) {
            result = attempt(email, trafficClass, pending, domains);
        } else if (deliveries.isEmpty()) {
            result = email.state().failed(NO_RECIPIENTS_ERROR, clock.instant());
        } else {
            // Пока письмо ждало после прерванной попытки, отчёты показали, что сервер его тогда принял
            log.info("Email {}: all recipients are covered by reports of the interrupted attempt, not sent again",
                     email.id());
            result = email.state().sent(clock.instant());
        }

        List<Delivery> changed = changedDeliveries(result, pending);
        List<DeliveryEvent> events = changed.stream().filter(Delivery::isFinal).map(DeliveryEvent::of).toList();
        if (!emailRepository.saveAttemptResult(result, changed, events)) {
            // Аренда истекла во время отправки, письмо уже у другого обработчика: его итог и будет записан
            log.warn("Email {}: result {} of attempt {} is stale and dropped",
                     email.id(), result.status(), result.attempts());
            return DispatchResult.DISPATCHED;
        }
        if (result.status() == EmailStatus.DEFERRED) {
            dispatchQueue.enqueue(result.id(), trafficClass, result.availableAt());
        }
        return DispatchResult.DISPATCHED;
    }

    /**
     * Берёт разрешения темпа на серверы всех получателей. Если какой-то сервер сейчас не принимает
     * или общий счётчик недоступен, письмо возвращается в ожидание без отправки.
     *
     * @return пусто — можно отправлять; иначе — на сколько письмо отложено
     */
    private Optional<Duration> acquireDestinations(Email email, TrafficClass trafficClass, Set<String> domains) {
        Optional<Duration> wait;
        try {
            wait = destinationLimiter.tryAcquire(domains);
        } catch (RuntimeException e) {
            log.warn("Email {}: rate of recipient servers is unavailable, sending is postponed", email.id(), e);
            wait = Optional.of(properties.getDispatch().getDestinationLimit().getUnavailableWait());
        }
        if (wait.isEmpty()) {
            return wait;
        }
        Instant now = clock.instant();
        EmailState postponed = email.state().postponed(now, now.plus(wait.get()));
        if (emailRepository.savePostponed(postponed)) {
            dispatchQueue.enqueue(postponed.id(), trafficClass, postponed.availableAt());
            metrics.postponed(trafficClass);
        }
        return wait;
    }

    private static Set<String> domainsOf(List<Delivery> deliveries) {
        return deliveries.stream()
                .map(delivery -> delivery.recipient().substring(delivery.recipient().lastIndexOf(ADDRESS_SEPARATOR) + 1))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private EmailState attempt(Email email, TrafficClass trafficClass, List<Delivery> pending, Set<String> domains) {
        EmailState sending = email.state();
        Instant started = clock.instant();
        try (LeaseKeeper.Lease ignored = leaseKeeper.keep(sending)) {
            emailTransport.send(email, pending.stream().map(Delivery::recipient).toList());
            Instant now = clock.instant();
            EmailState sent = sending.sent(now);
            rateLimiter.onResult(SendSignal.ACCEPTED, now);
            reportToDestinations(domains, SendSignal.ACCEPTED);
            metrics.attemptCompleted(sent, trafficClass, null, Duration.between(started, now));
            log.info("Email {} sent to {} recipients, attempt {}", email.id(), pending.size(), sending.attempts());
            return sent;
        } catch (EmailTransportException e) {
            DeliveryFailure failure = e.getFailure();
            Instant now = clock.instant();
            Optional<Instant> nextAttemptAt = retryPolicy.nextAttemptAt(sending.attempts(), failure, now);
            log.warn("Email {} failed, attempt {}, reply code {}, status {}, next attempt at {}",
                     email.id(), sending.attempts(), failure.replyCode(), failure.enhancedStatus(),
                     nextAttemptAt.orElse(null), e);
            EmailState result = nextAttemptAt
                    .map(at -> sending.deferred(failure.message(), now, at))
                    .orElseGet(() -> sending.failed(failure.message(), now));
            rateLimiter.onResult(SendSignal.of(failure), now);
            reportToDestinations(domains, SendSignal.of(failure));
            metrics.attemptCompleted(result, trafficClass, failure, Duration.between(started, now));
            return result;
        }
    }

    // Сбой при подстройке темпа не должен влиять на итог уже сделанной отправки
    private void reportToDestinations(Set<String> domains, SendSignal signal) {
        try {
            destinationLimiter.onResult(domains, signal);
        } catch (RuntimeException e) {
            log.warn("Rate of recipient servers {} was not adjusted after {}", domains, signal, e);
        }
    }

    // Отложенное письмо ждёт следующей попытки — доставки его получателям не меняются
    private static List<Delivery> changedDeliveries(EmailState result, List<Delivery> pending) {
        return switch (result.status()) {
            case SENT -> pending.stream().map(delivery -> delivery.sent(result.attempts(), result.updatedAt())).toList();
            case FAILED -> pending.stream()
                    .map(delivery -> delivery.undelivered(result.lastError(), result.updatedAt()))
                    .toList();
            default -> List.of();
        };
    }
}
