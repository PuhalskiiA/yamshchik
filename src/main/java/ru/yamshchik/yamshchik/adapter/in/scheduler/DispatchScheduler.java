package ru.yamshchik.yamshchik.adapter.in.scheduler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.yamshchik.yamshchik.application.port.in.DispatchEmailsUseCase;
import ru.yamshchik.yamshchik.application.port.in.DispatchEmailsUseCase.DispatchResult;
import ru.yamshchik.yamshchik.config.ScheduleConfig;
import ru.yamshchik.yamshchik.config.properties.YamshchikProperties;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;


/**
 * Периодически опрашивает очередь и отправляет письма несколькими обработчиками, пока она не опустеет.
 */
@Component
@Slf4j
public class DispatchScheduler {

    // Запас на округление: разрешение должно успеть накопиться к пробуждению
    private static final Duration MIN_WAIT = Duration.ofMillis(1);

    private final DispatchEmailsUseCase dispatchEmailsUseCase;

    private final ExecutorService executor;

    private final int workers;

    private final Duration pollInterval;

    public DispatchScheduler(
            DispatchEmailsUseCase dispatchEmailsUseCase,
            @Qualifier(ScheduleConfig.DISPATCH_EXECUTOR_BEAN) ExecutorService executor,
            YamshchikProperties properties
    ) {
        this.dispatchEmailsUseCase = dispatchEmailsUseCase;
        this.executor = executor;
        this.workers = properties.getDispatch().getWorkers();
        this.pollInterval = properties.getDispatch().getPollInterval();
    }

    @Scheduled(fixedDelayString = "${yamshchik.dispatch.poll-interval}")
    public void drainQueue() throws InterruptedException {
        List<Callable<Void>> drains = Collections.nCopies(workers, this::drain);
        for (Future<Void> drain : executor.invokeAll(drains)) {
            try {
                drain.get();
            } catch (ExecutionException e) {
                // Сбой одного обработчика не должен останавливать остальные и следующие опросы
                log.error("Dispatch worker failed:", e.getCause());
            }
        }
    }

    // Если письмо не ушло только из-за темпа и разрешение появится раньше следующего опроса, обработчик ждёт его
    private Void drain() throws InterruptedException {
        while (true) {
            DispatchResult result = dispatchEmailsUseCase.dispatchNext();
            if (result.dispatched()) {
                continue;
            }
            Duration wait = result.rateLimitedFor();
            if (wait == null || wait.compareTo(pollInterval) >= 0) {
                return null;
            }
            Thread.sleep(wait.plus(MIN_WAIT));
        }
    }
}
