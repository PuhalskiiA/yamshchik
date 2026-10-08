package ru.yamshchik.yamshchik.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.yamshchik.yamshchik.application.port.in.DispatchEmailsUseCase;
import ru.yamshchik.yamshchik.application.port.out.DispatchQueue;
import ru.yamshchik.yamshchik.application.port.out.EmailMetrics;
import ru.yamshchik.yamshchik.application.port.out.EmailRepository;
import ru.yamshchik.yamshchik.application.port.out.EmailTransport;
import ru.yamshchik.yamshchik.application.service.retry.RetryPolicy;
import ru.yamshchik.yamshchik.config.exception.type.EmailTransportException;
import ru.yamshchik.yamshchik.config.properties.YamshchikProperties;
import ru.yamshchik.yamshchik.domain.DeliveryFailure;
import ru.yamshchik.yamshchik.domain.Email;
import ru.yamshchik.yamshchik.domain.EmailState;
import ru.yamshchik.yamshchik.domain.EmailStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;


@Service
@RequiredArgsConstructor
@Slf4j
public class EmailDispatchService implements DispatchEmailsUseCase {

    private final EmailRepository emailRepository;

    private final DispatchQueue dispatchQueue;

    private final EmailTransport emailTransport;

    private final RetryPolicy retryPolicy;

    private final EmailMetrics metrics;

    private final YamshchikProperties properties;

    private final Clock clock;

    @Override
    public boolean dispatchNext() {
        Optional<Email> claimed = dispatchQueue.claimNext(clock.instant(), properties.getDispatch().getLease());
        if (claimed.isEmpty()) {
            return false;
        }

        Email email = claimed.get();
        EmailState result = attempt(email);
        if (!emailRepository.saveAttemptResult(result)) {
            // Аренда истекла во время отправки, письмо уже у другого обработчика: его итог и будет записан
            log.warn("Email {}: result {} of attempt {} is stale and dropped",
                     email.id(), result.status(), result.attempts());
            return true;
        }
        if (result.status() == EmailStatus.DEFERRED) {
            dispatchQueue.enqueue(result.id(), result.availableAt());
        }
        return true;
    }

    private EmailState attempt(Email email) {
        EmailState sending = email.state();
        Instant started = clock.instant();
        try {
            emailTransport.send(email);
            Instant now = clock.instant();
            EmailState sent = sending.sent(now);
            metrics.attemptCompleted(sent, null, Duration.between(started, now));
            log.info("Email {} sent, attempt {}", email.id(), sending.attempts());
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
            metrics.attemptCompleted(result, failure, Duration.between(started, now));
            return result;
        }
    }
}
