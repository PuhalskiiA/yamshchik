package ru.yamshchik.yamshchik.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.yamshchik.yamshchik.application.port.in.DispatchEmailsUseCase;
import ru.yamshchik.yamshchik.application.port.in.DispatchEmailsUseCase.DispatchResult;
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


@Service
@RequiredArgsConstructor
@Slf4j
public class EmailDispatchService implements DispatchEmailsUseCase {

    private static final String NO_RECIPIENTS_ERROR = "No recipients are waiting for this email";

    private static final List<TrafficClass> DISPATCH_ORDER = List.of(TrafficClass.URGENT, TrafficClass.BULK);

    private final EmailRepository emailRepository;

    private final DispatchQueue dispatchQueue;

    private final EmailTransport emailTransport;

    private final RetryPolicy retryPolicy;

    private final SendRateLimiter rateLimiter;

    private final LeaseKeeper leaseKeeper;

    private final EmailMetrics metrics;

    private final YamshchikProperties properties;

    private final Clock clock;

    /**
     * Срочные письма берутся первыми; массовые — если на них хватает темпа. Разрешение берётся до захвата письма:
     * захваченное письмо пришлось бы возвращать в очередь, а неиспользованное разрешение просто возвращается.
     */
    @Override
    public DispatchResult dispatchNext() {
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
                dispatch(claimed.get(), trafficClass);
                return DispatchResult.DISPATCHED;
            }
            rateLimiter.release(permit.get());
            urgentIdle |= trafficClass == TrafficClass.URGENT;
        }
        return rateLimited
                ? DispatchResult.rateLimited(rateLimiter.timeUntilPermit(clock.instant()))
                : DispatchResult.IDLE;
    }

    private void dispatch(Email email, TrafficClass trafficClass) {
        // Письмо уходит только тем, кто его ещё не получил: при повторе после отчёта об отказе — части получателей
        List<Delivery> deliveries = emailRepository.findDeliveries(email.id());
        List<Delivery> pending = deliveries.stream()
                .filter(delivery -> delivery.status() == DeliveryStatus.PENDING)
                .toList();
        EmailState result;
        if (!pending.isEmpty()) {
            result = attempt(email, trafficClass, pending);
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
            return;
        }
        if (result.status() == EmailStatus.DEFERRED) {
            dispatchQueue.enqueue(result.id(), trafficClass, result.availableAt());
        }
    }

    private EmailState attempt(Email email, TrafficClass trafficClass, List<Delivery> pending) {
        EmailState sending = email.state();
        Instant started = clock.instant();
        try (LeaseKeeper.Lease ignored = leaseKeeper.keep(sending)) {
            emailTransport.send(email, pending.stream().map(Delivery::recipient).toList());
            Instant now = clock.instant();
            EmailState sent = sending.sent(now);
            rateLimiter.onResult(SendSignal.ACCEPTED, now);
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
            metrics.attemptCompleted(result, trafficClass, failure, Duration.between(started, now));
            return result;
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
