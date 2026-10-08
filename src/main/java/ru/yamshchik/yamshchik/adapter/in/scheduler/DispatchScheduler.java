package ru.yamshchik.yamshchik.adapter.in.scheduler;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.yamshchik.yamshchik.application.port.in.DispatchEmailsUseCase;


/**
 * Периодически опрашивает очередь и отправляет письма, пока она не опустеет.
 */
@Component
@RequiredArgsConstructor
public class DispatchScheduler {

    private final DispatchEmailsUseCase dispatchEmailsUseCase;

    @Scheduled(fixedDelayString = "${yamshchik.dispatch.poll-interval}")
    public void drainQueue() {
        boolean dispatched;
        do {
            dispatched = dispatchEmailsUseCase.dispatchNext();
        } while (dispatched);
    }
}
