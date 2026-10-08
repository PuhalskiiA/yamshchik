package ru.yamshchik.yamshchik.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ru.yamshchik.yamshchik.application.port.in.DispatchEmailsUseCase;
import ru.yamshchik.yamshchik.application.port.out.DispatchQueue;
import ru.yamshchik.yamshchik.application.port.out.EmailRepository;
import ru.yamshchik.yamshchik.application.port.out.EmailTransport;
import ru.yamshchik.yamshchik.application.service.retry.RetryPolicy;
import ru.yamshchik.yamshchik.config.exception.type.EmailTransportException;
import ru.yamshchik.yamshchik.domain.DeliveryFailure;
import ru.yamshchik.yamshchik.domain.Email;
import ru.yamshchik.yamshchik.domain.EmailState;
import ru.yamshchik.yamshchik.domain.EmailStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;


@RequiredArgsConstructor
@Slf4j
public class EmailDispatchService implements DispatchEmailsUseCase {

    private final EmailRepository emailRepository;

    private final DispatchQueue dispatchQueue;

    private final EmailTransport emailTransport;

    private final RetryPolicy retryPolicy;

    private final Duration lease;

    private final Clock clock;

    @Override
    public boolean dispatchNext() {
        Optional<Email> claimed = dispatchQueue.claimNext(clock.instant(), lease);
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
        try {
            emailTransport.send(email);
            log.info("Email {} sent, attempt {}", email.id(), sending.attempts());
            return sending.sent(clock.instant());
        } catch (EmailTransportException e) {
            DeliveryFailure failure = e.getFailure();
            Instant now = clock.instant();
            Optional<Instant> nextAttemptAt = retryPolicy.nextAttemptAt(sending.attempts(), failure, now);
            log.warn("Email {} failed, attempt {}, reply code {}, status {}, next attempt at {}",
                     email.id(), sending.attempts(), failure.replyCode(), failure.enhancedStatus(),
                     nextAttemptAt.orElse(null), e);
            return nextAttemptAt
                    .map(at -> sending.deferred(failure.message(), now, at))
                    .orElseGet(() -> sending.failed(failure.message(), now));
        }
    }
}
