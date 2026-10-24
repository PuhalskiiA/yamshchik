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
import ru.yamshchik.yamshchik.config.properties.DispatchProperties;
import ru.yamshchik.yamshchik.config.properties.YamshchikProperties;
import ru.yamshchik.yamshchik.domain.EmailState;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static ru.yamshchik.yamshchik.TestData.LEASE;
import static ru.yamshchik.yamshchik.TestData.NOW;


@ExtendWith(MockitoExtension.class)
@DisplayName("Продление аренды письма на время отправки")
class LeaseKeeperTest {

    @Mock
    private ScheduledExecutorService scheduler;

    @Mock
    private ScheduledFuture<?> renewal;

    @Mock
    private DispatchQueue dispatchQueue;

    private LeaseKeeper leaseKeeper;

    private final EmailState sending = TestData.sendingEmail().state();

    @BeforeEach
    void setUp() {
        DispatchProperties dispatch = new DispatchProperties();
        dispatch.setLease(LEASE);
        YamshchikProperties properties = new YamshchikProperties();
        properties.setDispatch(dispatch);
        leaseKeeper = new LeaseKeeper(scheduler, dispatchQueue, properties, Clock.fixed(NOW, ZoneOffset.UTC));
        doReturn(renewal).when(scheduler).scheduleAtFixedRate(any(), eq(100_000L), eq(100_000L),
                                                              eq(TimeUnit.MILLISECONDS));
    }

    @Test
    @DisplayName("Аренда продлевается на полный срок трижды за срок аренды")
    void renewsWithinLease() {
        leaseKeeper.keep(sending);

        Runnable renew = scheduledRenewal();
        renew.run();

        verify(dispatchQueue).extendLease(sending, NOW.plus(LEASE));
    }

    @Test
    @DisplayName("После отправки продление останавливается")
    void closeStopsRenewal() {
        LeaseKeeper.Lease lease = leaseKeeper.keep(sending);

        lease.close();

        verify(renewal).cancel(false);
    }

    @Test
    @DisplayName("Сбой продления не прерывает отправку")
    void renewalFailureIsSwallowed() {
        leaseKeeper.keep(sending);
        when(dispatchQueue.extendLease(any(), any())).thenThrow(new IllegalStateException("db is down"));

        Runnable renew = scheduledRenewal();

        assertThatCode(renew::run)
                .overridingErrorMessage("Исключение в задаче продления остановило бы все следующие продления")
                .doesNotThrowAnyException();
    }

    private Runnable scheduledRenewal() {
        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).scheduleAtFixedRate(captor.capture(), eq(100_000L), eq(100_000L), eq(TimeUnit.MILLISECONDS));
        return captor.getValue();
    }
}
