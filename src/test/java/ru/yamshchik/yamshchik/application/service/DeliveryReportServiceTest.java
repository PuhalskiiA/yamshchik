package ru.yamshchik.yamshchik.application.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.yamshchik.yamshchik.TestData;
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
import ru.yamshchik.yamshchik.domain.EmailId;
import ru.yamshchik.yamshchik.domain.EmailState;
import ru.yamshchik.yamshchik.domain.EmailStatus;
import ru.yamshchik.yamshchik.domain.Priority;
import ru.yamshchik.yamshchik.domain.ReportAction;
import ru.yamshchik.yamshchik.domain.TrafficClass;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static ru.yamshchik.yamshchik.TestData.LEASE;
import static ru.yamshchik.yamshchik.TestData.NOW;


@ExtendWith(MockitoExtension.class)
@DisplayName("Обработка отчётов о доставке")
class DeliveryReportServiceTest {

    private static final String RECIPIENT = "user@example.org";

    private static final Duration IMPORTANT_TIMEOUT = Duration.ofMinutes(30);

    private static final int BATCH_SIZE = 10;

    private static final Instant NEXT_ATTEMPT_AT = NOW.plusSeconds(30);

    @Mock
    private DeliveryReportSource reportSource;

    @Mock
    private EmailRepository emailRepository;

    @Mock
    private DispatchQueue dispatchQueue;

    @Mock
    private RetryPolicy retryPolicy;

    @Mock
    private EmailMetrics metrics;

    private final EmailId emailId = EmailId.newId();

    // Что обработчик ответил ящику по каждому письму: true — отчёт обработан и письмо помечается прочитанным
    private final List<Boolean> acknowledged = new ArrayList<>();

    private DeliveryReportService service;

    @BeforeEach
    void setUp() {
        ReportProperties reports = new ReportProperties();
        reports.setBatchSize(BATCH_SIZE);
        reports.setImportantTimeout(IMPORTANT_TIMEOUT);
        YamshchikProperties properties = new YamshchikProperties();
        properties.setReports(reports);
        service = new DeliveryReportService(reportSource, emailRepository, dispatchQueue, retryPolicy, metrics,
                                            properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("Отчёт о доставке: DELIVERED и событие")
    void delivered() {
        waiting(1);
        saves(true);

        process(report(1, ReportAction.DELIVERED, "2.0.0"));

        Delivery saved = savedDelivery();
        assertThat(saved.status()).isEqualTo(DeliveryStatus.DELIVERED);
        assertThat(savedEvent().outcome())
                .overridingErrorMessage("Об окончательном исходе должно быть событие")
                .isEqualTo(DeliveryStatus.DELIVERED);
        assertThat(acknowledged).containsExactly(true);
        verify(metrics).reportApplied(ReportAction.DELIVERED, TrafficClass.BULK, Duration.ZERO);
    }

    @Test
    @DisplayName("Отчёт о передаче дальше считается доставкой, действие сохраняется")
    void relayed() {
        waiting(1);
        saves(true);

        process(report(1, ReportAction.RELAYED, "2.0.0"));

        assertThat(savedEvent()).satisfies(event -> {
            assertThat(event.outcome()).isEqualTo(DeliveryStatus.DELIVERED);
            assertThat(event.action())
                    .overridingErrorMessage("Потребитель должен видеть, что письмо лишь передано дальше")
                    .isEqualTo(ReportAction.RELAYED);
        });
    }

    @Test
    @DisplayName("Отчёт о задержке: ничего не отправляется, события нет")
    void delayed() {
        waiting(1);
        saves(true);

        process(report(1, ReportAction.DELAYED, "4.0.0"));

        verify(emailRepository).saveReportResult(any(), any(), isNull(), isNull());
        assertThat(savedDelivery().status()).isEqualTo(DeliveryStatus.SENT);
        verify(dispatchQueue, never()).enqueue(any(), any(), any());
    }

    @Test
    @DisplayName("Отказ с постоянной причиной: UNDELIVERED и событие, повтора нет")
    void permanentFailure() {
        waiting(1);
        saves(true);

        process(report(1, ReportAction.FAILED, "5.1.1"));

        assertThat(savedDelivery().status()).isEqualTo(DeliveryStatus.UNDELIVERED);
        assertThat(savedEvent().outcome()).isEqualTo(DeliveryStatus.UNDELIVERED);
        verify(dispatchQueue, never()).enqueue(any(), any(), any());
        verify(retryPolicy, never()).nextAttemptAt(anyInt(), any(), any());
    }

    @Test
    @DisplayName("Отказ с временной причиной: доставка ждёт отправки, письмо откладывается и встаёт в очередь")
    void temporaryFailureIsRetried() {
        waiting(1);
        EmailState email = emailIn(EmailStatus.SENT);
        when(retryPolicy.nextAttemptAt(eq(1), any(DeliveryFailure.class), eq(NOW)))
                .thenReturn(Optional.of(NEXT_ATTEMPT_AT));
        saves(true);

        process(report(1, ReportAction.FAILED, "4.0.0"));

        assertThat(savedDelivery().status())
                .overridingErrorMessage("Получатель, по которому пришёл временный отказ, должен ждать повторной отправки")
                .isEqualTo(DeliveryStatus.PENDING);
        EmailState deferred = savedEmail();
        assertThat(deferred.status()).isEqualTo(EmailStatus.DEFERRED);
        assertThat(deferred.availableAt()).isEqualTo(NEXT_ATTEMPT_AT);
        assertThat(deferred.attempts())
                .overridingErrorMessage("Отчёт не должен менять число попыток письма: по нему сверяется запись")
                .isEqualTo(email.attempts());
        verify(dispatchQueue).enqueue(emailId, TrafficClass.BULK, NEXT_ATTEMPT_AT);
        verify(emailRepository).saveReportResult(any(), any(), any(), isNull());
    }

    @Test
    @DisplayName("Письмо уже ждёт повтора для других получателей: этот уйдёт вместе с ними")
    void temporaryFailureJoinsPendingRetry() {
        waiting(1);
        emailIn(EmailStatus.DEFERRED);
        when(retryPolicy.nextAttemptAt(eq(1), any(DeliveryFailure.class), eq(NOW)))
                .thenReturn(Optional.of(NEXT_ATTEMPT_AT));
        saves(true);

        process(report(1, ReportAction.FAILED, "4.0.0"));

        assertThat(savedDelivery().status()).isEqualTo(DeliveryStatus.PENDING);
        verify(emailRepository).saveReportResult(any(), any(), isNull(), isNull());
        verify(dispatchQueue, never()).enqueue(any(), any(), any());
    }

    @Test
    @DisplayName("Временный отказ, но попытки исчерпаны: UNDELIVERED и событие")
    void temporaryFailureWithoutAttemptsLeft() {
        waiting(1);
        emailIn(EmailStatus.SENT);
        when(retryPolicy.nextAttemptAt(eq(1), any(DeliveryFailure.class), eq(NOW))).thenReturn(Optional.empty());
        saves(true);

        process(report(1, ReportAction.FAILED, "4.0.0"));

        assertThat(savedDelivery().status()).isEqualTo(DeliveryStatus.UNDELIVERED);
        assertThat(savedEvent().outcome()).isEqualTo(DeliveryStatus.UNDELIVERED);
        verify(dispatchQueue, never()).enqueue(any(), any(), any());
    }

    @Test
    @DisplayName("Временный отказ во время отправки письма: отчёт откладывается до следующего чтения")
    void temporaryFailureWhileSending() {
        waiting(1);
        emailIn(EmailStatus.SENDING);

        process(report(1, ReportAction.FAILED, "4.0.0"));

        assertThat(acknowledged)
                .overridingErrorMessage("Идущая отправка не включает этого получателя: отчёт нельзя терять")
                .containsExactly(false);
        verify(emailRepository, never()).saveReportResult(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Отчёт о прошлой попытке и повтор уже применённого отчёта пропускаются")
    void staleReportIsSkipped() {
        waiting(2);

        process(report(1, ReportAction.FAILED, "4.0.0"));

        assertThat(acknowledged)
                .overridingErrorMessage("Устаревший отчёт обрабатывать незачем, но и читать его снова не нужно")
                .containsExactly(true);
        verify(emailRepository, never()).saveReportResult(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Отчёт о незнакомой доставке пропускается")
    void unknownDelivery() {
        when(emailRepository.findDelivery(emailId, RECIPIENT)).thenReturn(Optional.empty());

        process(report(1, ReportAction.DELIVERED, "2.0.0"));

        assertThat(acknowledged).containsExactly(true);
    }

    @Test
    @DisplayName("Состояние изменилось параллельно: отчёт будет прочитан снова")
    void concurrentChange() {
        waiting(1);
        saves(false);

        process(report(1, ReportAction.DELIVERED, "2.0.0"));

        assertThat(acknowledged).containsExactly(false);
    }

    @Test
    @DisplayName("В письме несколько отчётов: прочитанным оно помечается, только если применены все")
    void severalReportsInOneMessage() {
        Delivery first = TestData.sentDelivery(emailId, RECIPIENT, 1);
        Delivery second = TestData.sentDelivery(emailId, "copy@example.org", 1);
        when(emailRepository.findDelivery(emailId, RECIPIENT)).thenReturn(Optional.of(first));
        when(emailRepository.findDelivery(emailId, "copy@example.org")).thenReturn(Optional.of(second));
        when(emailRepository.saveReportResult(any(), any(), any(), any())).thenReturn(true, false);

        process(report(1, ReportAction.DELIVERED, "2.0.0"),
                new DeliveryReport(emailId, 1, "copy@example.org", ReportAction.DELIVERED, "2.0.0", null));

        assertThat(acknowledged)
                .overridingErrorMessage("Неприменённый отчёт из письма не должен потеряться")
                .containsExactly(false);
        verify(emailRepository, times(2)).saveReportResult(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Инцидент заводится по важному письму без отчёта дольше срока")
    void incidents() {
        Delivery overdue = TestData.sentDelivery(emailId, RECIPIENT, 1);
        Delivery alreadyReported = TestData.sentDelivery(emailId, "copy@example.org", 1);
        when(emailRepository.findDeliveriesAwaitingReport(Priority.HIGH, NOW.minus(IMPORTANT_TIMEOUT), BATCH_SIZE))
                .thenReturn(List.of(overdue, alreadyReported));
        when(emailRepository.saveIncident(any())).thenReturn(true, false);

        int raised = service.raiseIncidents();

        assertThat(raised)
                .overridingErrorMessage("Инцидент, который уже завёл другой экземпляр, считаться не должен")
                .isEqualTo(1);
        ArgumentCaptor<Delivery> captor = ArgumentCaptor.forClass(Delivery.class);
        verify(emailRepository, times(2)).saveIncident(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(delivery -> assertThat(delivery.incidentAt()).isEqualTo(NOW));
        verify(metrics).incident();
    }

    @Test
    @DisplayName("Отчёт о прерванной попытке закрывает доставку без повторной отправки")
    void interruptedAttemptIsRecovered() {
        // Итог попытки 1 не записан: доставка всё ещё ждёт отправки, письмо ждёт отчёта о прерванной попытке
        when(emailRepository.findDelivery(emailId, RECIPIENT))
                .thenReturn(Optional.of(TestData.pendingDelivery(emailId, RECIPIENT)));
        EmailState interrupted = EmailState.accepted(emailId, NOW).startSending(NOW, LEASE)
                .interrupted(NOW.plus(LEASE), NOW.plus(LEASE).plusSeconds(120));
        when(emailRepository.findState(emailId)).thenReturn(Optional.of(interrupted));
        saves(true);

        process(report(1, ReportAction.RELAYED, "2.0.0"));

        verify(emailRepository).saveReportResult(eq(DeliveryStatus.PENDING), any(), isNull(), any());
        assertThat(savedDelivery()).satisfies(delivery -> {
            assertThat(delivery.status())
                    .overridingErrorMessage("Сервер принял письмо в прерванной попытке — доставка уже состоялась")
                    .isEqualTo(DeliveryStatus.DELIVERED);
            assertThat(delivery.attempt()).isEqualTo(1);
        });
        assertThat(acknowledged).containsExactly(true);
    }

    @Test
    @DisplayName("Отчёт о попытке, итог которой ещё записывается, откладывается")
    void reportOfAttemptInProgressIsPostponed() {
        when(emailRepository.findDelivery(emailId, RECIPIENT))
                .thenReturn(Optional.of(TestData.pendingDelivery(emailId, RECIPIENT)));
        emailIn(EmailStatus.SENDING);

        process(report(1, ReportAction.RELAYED, "2.0.0"));

        assertThat(acknowledged)
                .overridingErrorMessage("Итог попытки вот-вот запишется — отчёт нужно применить после этого, а не терять")
                .containsExactly(false);
        verify(emailRepository, never()).saveReportResult(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Отчёт о прерванной попытке, после которой уже была новая, не применяется")
    void reportOfOlderInterruptedAttemptIsSkipped() {
        when(emailRepository.findDelivery(emailId, RECIPIENT))
                .thenReturn(Optional.of(TestData.pendingDelivery(emailId, RECIPIENT)));
        EmailState secondAttempt = EmailState.accepted(emailId, NOW).startSending(NOW, LEASE)
                .deferred("451", NOW, NOW).startSending(NOW, LEASE);
        when(emailRepository.findState(emailId)).thenReturn(Optional.of(secondAttempt));

        process(report(1, ReportAction.RELAYED, "2.0.0"));

        assertThat(acknowledged).containsExactly(true);
        verify(emailRepository, never()).saveReportResult(any(), any(), any(), any());
    }

    private void process(DeliveryReport... reports) {
        when(reportSource.poll(eq(BATCH_SIZE), any())).thenAnswer(call -> {
            Predicate<List<DeliveryReport>> handler = call.getArgument(1);
            acknowledged.add(handler.test(List.of(reports)));
            return 1;
        });
        service.processReports();
    }

    private void waiting(int attempt) {
        when(emailRepository.findDelivery(emailId, RECIPIENT))
                .thenReturn(Optional.of(TestData.sentDelivery(emailId, RECIPIENT, attempt)));
    }

    private EmailState emailIn(EmailStatus status) {
        EmailState sending = EmailState.accepted(emailId, NOW).startSending(NOW, LEASE);
        EmailState state = switch (status) {
            case SENDING -> sending;
            case SENT -> sending.sent(NOW);
            case DEFERRED -> sending.deferred("4.0.0", NOW, NEXT_ATTEMPT_AT);
            default -> throw new IllegalArgumentException(status.name());
        };
        when(emailRepository.findState(emailId)).thenReturn(Optional.of(state));
        return state;
    }

    private void saves(boolean result) {
        when(emailRepository.saveReportResult(any(), any(), any(), any())).thenReturn(result);
    }

    private DeliveryReport report(int attempt, ReportAction action, String status) {
        return new DeliveryReport(emailId, attempt, RECIPIENT, action, status, "smtp; " + status);
    }

    private Delivery savedDelivery() {
        ArgumentCaptor<Delivery> captor = ArgumentCaptor.forClass(Delivery.class);
        verify(emailRepository).saveReportResult(any(), captor.capture(), any(), any());
        return captor.getValue();
    }

    private EmailState savedEmail() {
        ArgumentCaptor<EmailState> captor = ArgumentCaptor.forClass(EmailState.class);
        verify(emailRepository).saveReportResult(any(), any(), captor.capture(), any());
        return captor.getValue();
    }

    private DeliveryEvent savedEvent() {
        ArgumentCaptor<DeliveryEvent> captor = ArgumentCaptor.forClass(DeliveryEvent.class);
        verify(emailRepository).saveReportResult(any(), any(), any(), captor.capture());
        return captor.getValue();
    }
}
