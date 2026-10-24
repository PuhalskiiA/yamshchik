package ru.yamshchik.yamshchik.application.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.yamshchik.yamshchik.TestData;
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
import ru.yamshchik.yamshchik.config.properties.DestinationLimitProperties;
import ru.yamshchik.yamshchik.config.properties.DispatchProperties;
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
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static ru.yamshchik.yamshchik.TestData.LEASE;
import static ru.yamshchik.yamshchik.TestData.NOW;


@ExtendWith(MockitoExtension.class)
@DisplayName("Отправка писем из очереди")
class EmailDispatchServiceTest {

    private static final DeliveryFailure TEMPORARY = new DeliveryFailure(true, 451, "4.7.1", "try later");

    private static final DeliveryFailure PERMANENT = new DeliveryFailure(false, 550, "5.1.1", "no such user");

    private static final String FIRST = "first@example.org";

    private static final String SECOND = "second@other.org";

    private static final Duration UNAVAILABLE_WAIT = Duration.ofSeconds(1);

    @Mock
    private EmailRepository emailRepository;

    @Mock
    private DispatchQueue dispatchQueue;

    @Mock
    private EmailTransport emailTransport;

    @Mock
    private RetryPolicy retryPolicy;

    @Mock
    private EmailMetrics metrics;

    @Mock
    private SendRateLimiter rateLimiter;

    @Mock
    private LeaseKeeper leaseKeeper;

    @Mock
    private LeaseKeeper.Lease lease;

    @Mock
    private DestinationRateLimiter destinationLimiter;

    private EmailDispatchService service;

    @BeforeEach
    void setUp() {
        DestinationLimitProperties destinationLimit = new DestinationLimitProperties();
        destinationLimit.setUnavailableWait(UNAVAILABLE_WAIT);
        DispatchProperties dispatch = new DispatchProperties();
        dispatch.setLease(LEASE);
        dispatch.setDestinationLimit(destinationLimit);
        YamshchikProperties properties = new YamshchikProperties();
        properties.setDispatch(dispatch);
        service = new EmailDispatchService(emailRepository, dispatchQueue, emailTransport, retryPolicy, rateLimiter,
                                           leaseKeeper, destinationLimiter, metrics, properties,
                                           Clock.fixed(NOW, ZoneOffset.UTC));
        // По умолчанию темп на серверы получателей ничего не ограничивает
        lenient().when(destinationLimiter.available()).thenReturn(true);
        lenient().when(destinationLimiter.tryAcquire(any())).thenReturn(Optional.empty());
        // По умолчанию темп не ограничивает, срочных писем нет
        lenient().when(rateLimiter.tryAcquire(any(), anyBoolean(), any()))
                .thenAnswer(call -> Optional.of(new Permit(call.getArgument(0))));
        lenient().when(dispatchQueue.claimNext(eq(TrafficClass.URGENT), any(), any())).thenReturn(Optional.empty());
        lenient().when(leaseKeeper.keep(any())).thenReturn(lease);
    }

    @Test
    @DisplayName("Пустая очередь: отправлять нечего, разрешения темпа возвращаются")
    void emptyQueue() {
        when(dispatchQueue.claimNext(TrafficClass.BULK, NOW, LEASE)).thenReturn(Optional.empty());

        DispatchResult result = service.dispatchNext();

        assertThat(result)
                .overridingErrorMessage("При пустой очереди обработчик должен сообщить, что писем нет, а не что упёрся в темп")
                .isEqualTo(DispatchResult.IDLE);
        verify(rateLimiter).release(new Permit(TrafficClass.URGENT));
        verify(rateLimiter).release(new Permit(TrafficClass.BULK));
        verifyNoInteractions(emailTransport, emailRepository, metrics);
    }

    @Test
    @DisplayName("Срочное письмо берётся раньше массового")
    void urgentFirst() {
        Email urgent = TestData.sendingEmail();
        when(dispatchQueue.claimNext(TrafficClass.URGENT, NOW, LEASE)).thenReturn(Optional.of(urgent));
        when(emailRepository.findDeliveries(urgent.id()))
                .thenReturn(List.of(TestData.pendingDelivery(urgent.id(), FIRST)));
        when(emailRepository.saveAttemptResult(any(), anyList(), anyList())).thenReturn(true);

        service.dispatchNext();

        verify(emailTransport).send(urgent, List.of(FIRST));
        verify(dispatchQueue, never()).claimNext(eq(TrafficClass.BULK), any(), any());
        verify(metrics).attemptCompleted(any(), eq(TrafficClass.URGENT), isNull(), any());
    }

    @Test
    @DisplayName("Срочных нет — массовым разрешено занять их долю темпа")
    void bulkBorrowsWhenUrgentIdle() {
        when(dispatchQueue.claimNext(TrafficClass.BULK, NOW, LEASE)).thenReturn(Optional.empty());

        service.dispatchNext();

        verify(rateLimiter).tryAcquire(TrafficClass.URGENT, false, NOW);
        verify(rateLimiter).tryAcquire(TrafficClass.BULK, true, NOW);
    }

    @Test
    @DisplayName("Темп не позволяет: письма не берутся, обработчик узнаёт, сколько ждать")
    void rateLimited() {
        when(rateLimiter.tryAcquire(any(), anyBoolean(), any())).thenReturn(Optional.empty());
        when(rateLimiter.timeUntilPermit(NOW)).thenReturn(Duration.ofMillis(300));

        DispatchResult result = service.dispatchNext();

        assertThat(result.rateLimitedFor())
                .overridingErrorMessage("Обработчик должен подождать разрешения, а не ждать следующего опроса очереди")
                .isEqualTo(Duration.ofMillis(300));
        verify(dispatchQueue, never()).claimNext(any(), any(), any());
    }

    @Test
    @DisplayName("Ответ сервера передаётся регулятору темпа, аренда держится на время отправки")
    void throttlingIsReported() {
        Email email = TestData.sendingEmail();
        claimed(email, TestData.pendingDelivery(email.id(), FIRST));
        DeliveryFailure throttled = new DeliveryFailure(true, 421, "4.7.0", "slow down");
        doThrow(new EmailTransportException(throttled, null)).when(emailTransport).send(email, List.of(FIRST));
        when(retryPolicy.nextAttemptAt(1, throttled, NOW)).thenReturn(Optional.of(NOW.plusSeconds(30)));
        when(emailRepository.saveAttemptResult(any(), anyList(), anyList())).thenReturn(true);

        service.dispatchNext();

        verify(rateLimiter).onResult(SendSignal.THROTTLED, NOW);
        InOrder order = inOrder(leaseKeeper, emailTransport, lease);
        order.verify(leaseKeeper).keep(email.state());
        order.verify(emailTransport).send(email, List.of(FIRST));
        order.verify(lease).close();
        verify(dispatchQueue).enqueue(email.id(), TrafficClass.BULK, NOW.plusSeconds(30));
    }

    @Test
    @DisplayName("Сервер принял письмо: письмо SENT, доставки ждут отчёта в этой попытке")
    void sent() {
        Email email = TestData.sendingEmail();
        claimed(email, TestData.pendingDelivery(email.id(), FIRST));
        when(emailRepository.saveAttemptResult(any(), anyList(), anyList())).thenReturn(true);

        DispatchResult dispatched = service.dispatchNext();

        EmailState result = savedResult();
        assertThat(dispatched).isEqualTo(DispatchResult.DISPATCHED);
        assertThat(result.status())
                .overridingErrorMessage("После успешной передачи письмо должно стать SENT, а не %s", result.status())
                .isEqualTo(EmailStatus.SENT);
        assertThat(result.sentAt()).isEqualTo(NOW);
        assertThat(savedDeliveries()).singleElement()
                .overridingErrorMessage("Доставка должна ждать отчёта о той попытке, в которой письмо ушло")
                .satisfies(delivery -> {
                    assertThat(delivery.status()).isEqualTo(DeliveryStatus.SENT);
                    assertThat(delivery.attempt()).isEqualTo(result.attempts());
                    assertThat(delivery.sentAt()).isEqualTo(NOW);
                });
        assertThat(savedEvents())
                .overridingErrorMessage("Пока отчёта нет, исход доставки не окончательный и событий быть не должно")
                .isEmpty();
        verify(metrics).attemptCompleted(eq(result), eq(TrafficClass.BULK), isNull(), any());
        verify(rateLimiter).onResult(SendSignal.ACCEPTED, NOW);
        verify(dispatchQueue, never()).enqueue(any(), any(), any());
    }

    @Test
    @DisplayName("Письмо уходит только тем получателям, которые его ещё не получили")
    void onlyPendingRecipientsAreSent() {
        Email email = TestData.sendingEmail();
        claimed(email, TestData.sentDelivery(email.id(), FIRST, 1), TestData.pendingDelivery(email.id(), SECOND));
        when(emailRepository.saveAttemptResult(any(), anyList(), anyList())).thenReturn(true);

        service.dispatchNext();

        verify(emailTransport).send(email, List.of(SECOND));
        assertThat(savedDeliveries()).extracting(Delivery::recipient)
                .overridingErrorMessage("Меняться должна только доставка, которая ждала отправки")
                .containsExactly(SECOND);
    }

    @Test
    @DisplayName("Временный отказ: письмо откладывается, доставки продолжают ждать отправки")
    void deferred() {
        Email email = TestData.sendingEmail();
        claimed(email, TestData.pendingDelivery(email.id(), FIRST));
        Instant nextAttemptAt = NOW.plusSeconds(30);
        doThrow(new EmailTransportException(TEMPORARY, null)).when(emailTransport).send(email, List.of(FIRST));
        when(retryPolicy.nextAttemptAt(1, TEMPORARY, NOW)).thenReturn(Optional.of(nextAttemptAt));
        when(emailRepository.saveAttemptResult(any(), anyList(), anyList())).thenReturn(true);

        service.dispatchNext();

        EmailState result = savedResult();
        assertThat(result.status())
                .overridingErrorMessage("Временный отказ должен давать DEFERRED, а не %s", result.status())
                .isEqualTo(EmailStatus.DEFERRED);
        assertThat(result.availableAt()).isEqualTo(nextAttemptAt);
        assertThat(result.lastError()).isEqualTo("try later");
        assertThat(savedDeliveries())
                .overridingErrorMessage("Отложенное письмо ещё уйдёт: доставки меняться не должны")
                .isEmpty();
        verify(dispatchQueue).enqueue(email.id(), TrafficClass.BULK, nextAttemptAt);
        verify(metrics).attemptCompleted(eq(result), eq(TrafficClass.BULK), eq(TEMPORARY), any());
    }

    @Test
    @DisplayName("Попыток больше не будет: письмо FAILED, доставки UNDELIVERED с событием на каждую")
    void failed() {
        Email email = TestData.sendingEmail();
        claimed(email, TestData.pendingDelivery(email.id(), FIRST), TestData.pendingDelivery(email.id(), SECOND));
        doThrow(new EmailTransportException(PERMANENT, null)).when(emailTransport).send(any(), anyList());
        when(retryPolicy.nextAttemptAt(1, PERMANENT, NOW)).thenReturn(Optional.empty());
        when(emailRepository.saveAttemptResult(any(), anyList(), anyList())).thenReturn(true);

        service.dispatchNext();

        EmailState result = savedResult();
        assertThat(result.status())
                .overridingErrorMessage("Без следующей попытки письмо должно стать FAILED, а не %s", result.status())
                .isEqualTo(EmailStatus.FAILED);
        assertThat(result.lastError()).isEqualTo("no such user");
        assertThat(savedDeliveries())
                .overridingErrorMessage("Получатели неотправленного письма должны получить окончательный отказ")
                .allSatisfy(delivery -> {
                    assertThat(delivery.status()).isEqualTo(DeliveryStatus.UNDELIVERED);
                    assertThat(delivery.diagnostic()).isEqualTo("no such user");
                });
        assertThat(savedEvents()).extracting(DeliveryEvent::recipient, DeliveryEvent::outcome)
                .overridingErrorMessage("Об отказе каждому получателю должно быть событие, даже без отчёта сервера")
                .containsExactly(tuple(FIRST, DeliveryStatus.UNDELIVERED),
                                 tuple(SECOND, DeliveryStatus.UNDELIVERED));
        verify(dispatchQueue, never()).enqueue(any(), any(), any());
        verify(metrics).attemptCompleted(eq(result), eq(TrafficClass.BULK), eq(PERMANENT), any());
        verify(emailTransport).send(email, List.of(FIRST, SECOND));
    }

    @Test
    @DisplayName("Отчёты о прерванной попытке закрыли всех получателей: письмо SENT без повторной отправки")
    void coveredByRecoveredReports() {
        Email email = TestData.sendingEmail();
        claimed(email, TestData.sentDelivery(email.id(), FIRST, 1));
        when(emailRepository.saveAttemptResult(any(), anyList(), anyList())).thenReturn(true);

        service.dispatchNext();

        assertThat(savedResult().status())
                .overridingErrorMessage("Сервер уже принял письмо в прерванной попытке — повторять его нельзя")
                .isEqualTo(EmailStatus.SENT);
        verifyNoInteractions(emailTransport);
    }

    @Test
    @DisplayName("У письма нет ни одного получателя: оно не отправляется и получает FAILED")
    void noDeliveries() {
        Email email = TestData.sendingEmail();
        claimed(email);
        when(emailRepository.saveAttemptResult(any(), anyList(), anyList())).thenReturn(true);

        service.dispatchNext();

        assertThat(savedResult().status()).isEqualTo(EmailStatus.FAILED);
        verifyNoInteractions(emailTransport);
    }

    @Test
    @DisplayName("Устаревший итог: письмо в очередь повторно не ставится")
    void staleResultIsDropped() {
        Email email = TestData.sendingEmail();
        claimed(email, TestData.pendingDelivery(email.id(), FIRST));
        doThrow(new EmailTransportException(TEMPORARY, null)).when(emailTransport).send(email, List.of(FIRST));
        when(retryPolicy.nextAttemptAt(1, TEMPORARY, NOW)).thenReturn(Optional.of(NOW.plusSeconds(30)));
        when(emailRepository.saveAttemptResult(any(), anyList(), anyList())).thenReturn(false);

        DispatchResult dispatched = service.dispatchNext();

        assertThat(dispatched)
                .overridingErrorMessage("Письмо было обработано, цикл опроса должен продолжаться")
                .isEqualTo(DispatchResult.DISPATCHED);
        verify(dispatchQueue, never()).enqueue(any(), any(), any());
    }

    @Test
    @DisplayName("Сервер получателя сейчас не принимает по темпу: письмо возвращается в ожидание без отправки")
    void postponedByDestinationRate() {
        Email email = TestData.sendingEmail();
        claimed(email, TestData.pendingDelivery(email.id(), FIRST), TestData.pendingDelivery(email.id(), SECOND));
        when(destinationLimiter.tryAcquire(Set.of("example.org", "other.org")))
                .thenReturn(Optional.of(Duration.ofMillis(700)));
        when(emailRepository.savePostponed(any())).thenReturn(true);

        DispatchResult result = service.dispatchNext();

        assertThat(result.rateLimitedFor())
                .overridingErrorMessage("Обработчик должен дождаться разрешения сервера, а не следующего опроса очереди")
                .isEqualTo(Duration.ofMillis(700));
        ArgumentCaptor<EmailState> postponed = ArgumentCaptor.forClass(EmailState.class);
        verify(emailRepository).savePostponed(postponed.capture());
        assertThat(postponed.getValue().status()).isEqualTo(EmailStatus.DEFERRED);
        assertThat(postponed.getValue().attempts())
                .overridingErrorMessage("Письмо серверу не передавалось — попытка не должна считаться")
                .isZero();
        assertThat(postponed.getValue().availableAt()).isEqualTo(NOW.plusMillis(700));
        verify(dispatchQueue).enqueue(email.id(), TrafficClass.BULK, NOW.plusMillis(700));
        verify(metrics).postponed(TrafficClass.BULK);
        verifyNoInteractions(emailTransport);
        verify(emailRepository, never()).saveAttemptResult(any(), anyList(), anyList());
    }

    @Test
    @DisplayName("Счётчик темпа недоступен: письма не захватываются, рассылка ждёт")
    void destinationLimiterUnavailable() {
        when(destinationLimiter.available()).thenReturn(false);

        DispatchResult result = service.dispatchNext();

        assertThat(result.rateLimitedFor())
                .overridingErrorMessage("Без Redis письма нельзя брать в отправку — их пришлось бы возвращать")
                .isEqualTo(UNAVAILABLE_WAIT);
        verify(dispatchQueue, never()).claimNext(any(), any(), any());
    }

    @Test
    @DisplayName("Redis отказал уже после захвата письма: письмо возвращается в ожидание")
    void destinationLimiterFailsAfterClaim() {
        Email email = TestData.sendingEmail();
        claimed(email, TestData.pendingDelivery(email.id(), FIRST));
        when(destinationLimiter.tryAcquire(any())).thenThrow(new IllegalStateException("redis is down"));
        when(emailRepository.savePostponed(any())).thenReturn(true);

        service.dispatchNext();

        verify(dispatchQueue).enqueue(email.id(), TrafficClass.BULK, NOW.plus(UNAVAILABLE_WAIT));
        verifyNoInteractions(emailTransport);
    }

    @Test
    @DisplayName("Ответ сервера подстраивает темп серверов всех получателей письма")
    void destinationsLearnFromResult() {
        Email email = TestData.sendingEmail();
        claimed(email, TestData.pendingDelivery(email.id(), FIRST), TestData.pendingDelivery(email.id(), SECOND));
        when(emailRepository.saveAttemptResult(any(), anyList(), anyList())).thenReturn(true);

        service.dispatchNext();

        verify(destinationLimiter).onResult(Set.of("example.org", "other.org"), SendSignal.ACCEPTED);
    }

    private void claimed(Email email, Delivery... deliveries) {
        when(dispatchQueue.claimNext(TrafficClass.BULK, NOW, LEASE)).thenReturn(Optional.of(email));
        when(emailRepository.findDeliveries(email.id())).thenReturn(List.of(deliveries));
    }

    private EmailState savedResult() {
        ArgumentCaptor<EmailState> captor = ArgumentCaptor.forClass(EmailState.class);
        verify(emailRepository).saveAttemptResult(captor.capture(), anyList(), anyList());
        return captor.getValue();
    }

    private List<Delivery> savedDeliveries() {
        ArgumentCaptor<List<Delivery>> captor = ArgumentCaptor.captor();
        verify(emailRepository).saveAttemptResult(any(), captor.capture(), anyList());
        return captor.getValue();
    }

    private List<DeliveryEvent> savedEvents() {
        ArgumentCaptor<List<DeliveryEvent>> captor = ArgumentCaptor.captor();
        verify(emailRepository).saveAttemptResult(any(), anyList(), captor.capture());
        return captor.getValue();
    }
}
