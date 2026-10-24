package ru.yamshchik.yamshchik.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.yamshchik.yamshchik.application.port.in.ProcessDeliveryReportsUseCase;
import ru.yamshchik.yamshchik.application.port.out.DeliveryReportSource;
import ru.yamshchik.yamshchik.application.port.out.DispatchQueue;
import ru.yamshchik.yamshchik.application.port.out.EmailMetrics;
import ru.yamshchik.yamshchik.application.port.out.EmailRepository;
import ru.yamshchik.yamshchik.application.service.retry.RetryPolicy;
import ru.yamshchik.yamshchik.config.properties.ReportProperties;
import ru.yamshchik.yamshchik.config.properties.YamshchikProperties;
import ru.yamshchik.yamshchik.domain.Delivery;
import ru.yamshchik.yamshchik.domain.DeliveryEvent;
import ru.yamshchik.yamshchik.domain.DeliveryFailure;
import ru.yamshchik.yamshchik.domain.DeliveryReport;
import ru.yamshchik.yamshchik.domain.DeliveryStatus;
import ru.yamshchik.yamshchik.domain.EmailState;
import ru.yamshchik.yamshchik.domain.EmailStatus;
import ru.yamshchik.yamshchik.domain.Priority;
import ru.yamshchik.yamshchik.domain.TrafficClass;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;


/**
 * Применяет отчёты о доставке к доставкам получателям. Отчёт, который нельзя применить прямо сейчас
 * (письмо как раз отправляется или состояние изменилось параллельно), остаётся в ящике и читается снова.
 * Повторное чтение безопасно: отчёт применяется только к доставке, которая ждёт его в той же попытке.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeliveryReportService implements ProcessDeliveryReportsUseCase {

    // Важное письмо: если отчёт о нём не пришёл вовремя, заводится инцидент
    private static final Priority IMPORTANT_PRIORITY = Priority.HIGH;

    private final DeliveryReportSource reportSource;

    private final EmailRepository emailRepository;

    private final DispatchQueue dispatchQueue;

    private final RetryPolicy retryPolicy;

    private final EmailMetrics metrics;

    private final YamshchikProperties properties;

    private final Clock clock;

    @Override
    public int processReports() {
        return reportSource.poll(properties.getReports().getBatchSize(),
                                 reports -> reports.stream().map(this::apply).reduce(true, Boolean::logicalAnd));
    }

    @Override
    public int raiseIncidents() {
        ReportProperties reports = properties.getReports();
        Instant now = clock.instant();
        List<Delivery> overdue = emailRepository.findDeliveriesAwaitingReport(
                IMPORTANT_PRIORITY, now.minus(reports.getImportantTimeout()), reports.getBatchSize());
        int raised = 0;
        for (Delivery delivery : overdue) {
            if (emailRepository.saveIncident(delivery.incident(now))) {
                log.error("Incident: no delivery report for important email {} to {} since {}, batch {}",
                          delivery.emailId(), delivery.recipient(), delivery.sentAt(), delivery.batchId());
                metrics.incident();
                raised++;
            }
        }
        return raised;
    }

    /**
     * @return false, если отчёт нужно обработать позже
     */
    private boolean apply(DeliveryReport report) {
        Optional<Delivery> found = emailRepository.findDelivery(report.emailId(), report.recipient());
        if (found.isEmpty()) {
            log.warn("Delivery report for unknown delivery is skipped: {}", report);
            return true;
        }
        Delivery delivery = found.get();
        DeliveryStatus expected = delivery.status();
        Instant now = clock.instant();
        if (isInterruptedAttempt(delivery, report)) {
            Recovery recovery = recovery(report);
            if (recovery == Recovery.LATER) {
                log.info("Delivery report is postponed, its attempt is still being recorded: {}", report);
                return false;
            }
            if (recovery == Recovery.SKIP) {
                return true;
            }
            // Отчёт о попытке, итог которой не записан: сервер письмо принял, повторять его не нужно
            log.info("Delivery report recovers interrupted attempt {} of email {} to {}",
                     report.attempt(), report.emailId(), report.recipient());
            delivery = delivery.recovered(report.attempt(), now);
        } else if (delivery.status() != DeliveryStatus.SENT || delivery.attempt() != report.attempt()) {
            // Повтор уже применённого отчёта или отчёт о прошлой попытке
            log.info("Delivery report is stale and skipped: {}, delivery is {} in attempt {}",
                     report, delivery.status(), delivery.attempt());
            return true;
        }

        log.info("Delivery report: email {} to {}, attempt {}, {} {}",
                 report.emailId(), report.recipient(), report.attempt(), report.action(), report.status());
        boolean applied = switch (report.action()) {
            case DELIVERED, RELAYED, EXPANDED -> saveFinal(expected, delivery.delivered(report, now));
            case DELAYED -> emailRepository.saveReportResult(expected, delivery.delayed(report, now), null, null);
            case FAILED -> report.temporaryFailure()
                    ? retry(expected, delivery, report, now)
                    : saveFinal(expected, delivery.undelivered(report, now));
        };
        if (applied) {
            metrics.reportApplied(report.action(), TrafficClass.of(delivery.priority()),
                                  Duration.between(delivery.sentAt(), now));
        }
        return applied;
    }

    // Доставка ждёт отправки, а отчёт пришёл о более новой попытке: её итог не был записан
    private static boolean isInterruptedAttempt(Delivery delivery, DeliveryReport report) {
        return delivery.status() == DeliveryStatus.PENDING && report.attempt() > delivery.attempt();
    }

    // Прерванную попытку можно восстановить, только если она последняя у письма и письмо сейчас не отправляется
    private Recovery recovery(DeliveryReport report) {
        Optional<EmailState> email = emailRepository.findState(report.emailId());
        if (email.isEmpty() || email.get().attempts() != report.attempt()) {
            log.info("Delivery report about an earlier attempt is skipped: {}", report);
            return Recovery.SKIP;
        }
        return email.get().status() == EmailStatus.SENDING ? Recovery.LATER : Recovery.RECOVER;
    }

    private boolean saveFinal(DeliveryStatus expected, Delivery delivery) {
        return emailRepository.saveReportResult(expected, delivery, null, DeliveryEvent.of(delivery));
    }

    // Сервер исчерпал свои попытки по временной причине: письмо уходит этому получателю ещё раз, пока не исчерпаны наши
    private boolean retry(DeliveryStatus expected, Delivery delivery, DeliveryReport report, Instant now) {
        Optional<EmailState> found = emailRepository.findState(delivery.emailId());
        if (found.isEmpty()) {
            return true;
        }
        EmailState email = found.get();
        if (email.status() == EmailStatus.SENDING) {
            // Отправка, которая идёт сейчас, этого получателя не включает: отчёт применим после неё
            return false;
        }

        DeliveryFailure failure = new DeliveryFailure(true, null, report.status(), report.diagnostic());
        Optional<Instant> nextAttemptAt = retryPolicy.nextAttemptAt(email.attempts(), failure, now);
        if (nextAttemptAt.isEmpty()) {
            return saveFinal(expected, delivery.undelivered(report, now));
        }
        // Если письмо уже ждёт повтора для других получателей, этот уйдёт вместе с ними
        EmailState deferred = email.status() == EmailStatus.SENT
                ? email.deferredByReport(report.diagnostic(), now, nextAttemptAt.get())
                : null;
        if (!emailRepository.saveReportResult(expected, delivery.retry(report, now), deferred, null)) {
            return false;
        }
        if (deferred != null) {
            dispatchQueue.enqueue(deferred.id(), TrafficClass.of(delivery.priority()), deferred.availableAt());
        }
        return true;
    }

    private enum Recovery {

        /**
         * Восстановить доставку по отчёту.
         */
        RECOVER,

        /**
         * Отчёт устарел: попытка не последняя.
         */
        SKIP,

        /**
         * Применить позже: эта попытка ещё идёт, и её итог вот-вот запишется.
         */
        LATER
    }
}
