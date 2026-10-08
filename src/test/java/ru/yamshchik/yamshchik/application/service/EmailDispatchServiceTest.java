package ru.yamshchik.yamshchik.application.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.yamshchik.yamshchik.TestData;
import ru.yamshchik.yamshchik.application.port.out.DispatchQueue;
import ru.yamshchik.yamshchik.application.port.out.EmailMetrics;
import ru.yamshchik.yamshchik.application.port.out.EmailRepository;
import ru.yamshchik.yamshchik.application.port.out.EmailTransport;
import ru.yamshchik.yamshchik.application.service.retry.RetryPolicy;
import ru.yamshchik.yamshchik.config.exception.type.EmailTransportException;
import ru.yamshchik.yamshchik.config.properties.DispatchProperties;
import ru.yamshchik.yamshchik.config.properties.YamshchikProperties;
import ru.yamshchik.yamshchik.domain.DeliveryFailure;
import ru.yamshchik.yamshchik.domain.Email;
import ru.yamshchik.yamshchik.domain.EmailState;
import ru.yamshchik.yamshchik.domain.EmailStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
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

    private EmailDispatchService service;

    @BeforeEach
    void setUp() {
        DispatchProperties dispatch = new DispatchProperties();
        dispatch.setLease(LEASE);
        YamshchikProperties properties = new YamshchikProperties();
        properties.setDispatch(dispatch);
        service = new EmailDispatchService(emailRepository, dispatchQueue, emailTransport, retryPolicy, metrics,
                                           properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("Пустая очередь: отправлять нечего")
    void emptyQueue() {
        when(dispatchQueue.claimNext(NOW, LEASE)).thenReturn(Optional.empty());

        boolean dispatched = service.dispatchNext();

        assertThat(dispatched)
                .overridingErrorMessage("При пустой очереди обработчик должен сообщить, что писем нет")
                .isFalse();
        verifyNoInteractions(emailTransport, emailRepository, metrics);
    }

    @Test
    @DisplayName("Сервер принял письмо: записывается SENT")
    void sent() {
        Email email = claimed();
        when(emailRepository.saveAttemptResult(any())).thenReturn(true);

        boolean dispatched = service.dispatchNext();

        EmailState result = savedResult();
        assertThat(dispatched).isTrue();
        assertThat(result.status())
                .overridingErrorMessage("После успешной передачи письмо должно стать SENT, а не %s", result.status())
                .isEqualTo(EmailStatus.SENT);
        assertThat(result.sentAt()).isEqualTo(NOW);
        verify(emailTransport).send(email);
        verify(metrics).attemptCompleted(eq(result), isNull(), any());
        verify(dispatchQueue, never()).enqueue(any(), any());
    }

    @Test
    @DisplayName("Временный отказ: письмо откладывается и возвращается в очередь")
    void deferred() {
        Email email = claimed();
        Instant nextAttemptAt = NOW.plusSeconds(30);
        doThrow(new EmailTransportException(TEMPORARY, null)).when(emailTransport).send(email);
        when(retryPolicy.nextAttemptAt(1, TEMPORARY, NOW)).thenReturn(Optional.of(nextAttemptAt));
        when(emailRepository.saveAttemptResult(any())).thenReturn(true);

        service.dispatchNext();

        EmailState result = savedResult();
        assertThat(result.status())
                .overridingErrorMessage("Временный отказ должен давать DEFERRED, а не %s", result.status())
                .isEqualTo(EmailStatus.DEFERRED);
        assertThat(result.availableAt()).isEqualTo(nextAttemptAt);
        assertThat(result.lastError()).isEqualTo("try later");
        verify(dispatchQueue).enqueue(email.id(), nextAttemptAt);
        verify(metrics).attemptCompleted(eq(result), eq(TEMPORARY), any());
    }

    @Test
    @DisplayName("Политика повторов отказала: записывается FAILED")
    void failed() {
        Email email = claimed();
        doThrow(new EmailTransportException(PERMANENT, null)).when(emailTransport).send(email);
        when(retryPolicy.nextAttemptAt(1, PERMANENT, NOW)).thenReturn(Optional.empty());
        when(emailRepository.saveAttemptResult(any())).thenReturn(true);

        service.dispatchNext();

        EmailState result = savedResult();
        assertThat(result.status())
                .overridingErrorMessage("Без следующей попытки письмо должно стать FAILED, а не %s", result.status())
                .isEqualTo(EmailStatus.FAILED);
        assertThat(result.lastError()).isEqualTo("no such user");
        verify(dispatchQueue, never()).enqueue(any(), any());
        verify(metrics).attemptCompleted(eq(result), eq(PERMANENT), any());
    }

    @Test
    @DisplayName("Устаревший итог: письмо в очередь повторно не ставится")
    void staleResultIsDropped() {
        Email email = claimed();
        doThrow(new EmailTransportException(TEMPORARY, null)).when(emailTransport).send(email);
        when(retryPolicy.nextAttemptAt(1, TEMPORARY, NOW)).thenReturn(Optional.of(NOW.plusSeconds(30)));
        when(emailRepository.saveAttemptResult(any())).thenReturn(false);

        boolean dispatched = service.dispatchNext();

        assertThat(dispatched)
                .overridingErrorMessage("Письмо было обработано, цикл опроса должен продолжаться")
                .isTrue();
        verify(dispatchQueue, never()).enqueue(any(), any());
    }

    private Email claimed() {
        Email email = TestData.sendingEmail();
        when(dispatchQueue.claimNext(NOW, LEASE)).thenReturn(Optional.of(email));
        return email;
    }

    private EmailState savedResult() {
        ArgumentCaptor<EmailState> captor = ArgumentCaptor.forClass(EmailState.class);
        verify(emailRepository).saveAttemptResult(captor.capture());
        return captor.getValue();
    }
}
