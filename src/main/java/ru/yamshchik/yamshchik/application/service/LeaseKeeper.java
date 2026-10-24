package ru.yamshchik.yamshchik.application.service;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;
import org.springframework.stereotype.Component;
import ru.yamshchik.yamshchik.application.port.out.DispatchQueue;
import ru.yamshchik.yamshchik.config.properties.YamshchikProperties;
import ru.yamshchik.yamshchik.domain.EmailState;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;


/**
 * Продлевает аренду письма, пока оно отправляется: долгая отправка большого письма не должна
 * отдать его второму обработчику и привести к повторной доставке.
 */
@Component
@Slf4j
public class LeaseKeeper {

    // Продление заранее, с запасом на две неудачные попытки продлить
    private static final int RENEWALS_PER_LEASE = 3;

    private static final String THREAD_PREFIX = "lease-";

    private final ScheduledExecutorService scheduler;

    private final DispatchQueue dispatchQueue;

    private final Duration lease;

    private final Clock clock;

    // Свой поток, а не бин: бин ScheduledExecutorService Spring взял бы для всех задач по расписанию,
    // и отправка, чтение отчётов и продление аренды ждали бы друг друга на одном потоке.
    // Продление — короткий запрос к БД, одного потока хватает на всех обработчиков
    @Autowired
    public LeaseKeeper(DispatchQueue dispatchQueue, YamshchikProperties properties, Clock clock) {
        this(Executors.newSingleThreadScheduledExecutor(new CustomizableThreadFactory(THREAD_PREFIX)),
             dispatchQueue, properties, clock);
    }

    LeaseKeeper(
            ScheduledExecutorService scheduler,
            DispatchQueue dispatchQueue,
            YamshchikProperties properties,
            Clock clock
    ) {
        this.scheduler = scheduler;
        this.dispatchQueue = dispatchQueue;
        this.lease = properties.getDispatch().getLease();
        this.clock = clock;
    }

    /**
     * Держит аренду письма, пока возвращённая аренда не закрыта.
     */
    public Lease keep(EmailState sending) {
        long period = Math.max(1, lease.toMillis() / RENEWALS_PER_LEASE);
        ScheduledFuture<?> renewal = scheduler.scheduleAtFixedRate(() -> renew(sending), period, period,
                                                                    TimeUnit.MILLISECONDS);
        return () -> renewal.cancel(false);
    }

    @PreDestroy
    public void close() {
        scheduler.shutdown();
    }

    private void renew(EmailState sending) {
        try {
            if (!dispatchQueue.extendLease(sending, clock.instant().plus(lease))) {
                log.warn("Email {}: lease of attempt {} cannot be extended, the email was taken over",
                         sending.id(), sending.attempts());
            }
        } catch (RuntimeException e) {
            log.warn("Email {}: lease of attempt {} was not extended", sending.id(), sending.attempts(), e);
        }
    }

    /**
     * Аренда, которая продлевается до закрытия.
     */
    @FunctionalInterface
    public interface Lease extends AutoCloseable {

        @Override
        void close();
    }
}
