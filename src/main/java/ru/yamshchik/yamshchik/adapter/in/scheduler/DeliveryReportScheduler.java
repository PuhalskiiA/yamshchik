package ru.yamshchik.yamshchik.adapter.in.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.yamshchik.yamshchik.application.port.in.ProcessDeliveryReportsUseCase;


/**
 * Периодически читает отчёты о доставке и заводит инциденты по важным письмам без отчёта.
 * За один раз читается одна пачка: отложенные отчёты остаются непрочитанными, и чтение «до опустения ящика»
 * крутилось бы на них без паузы.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DeliveryReportScheduler {

    private final ProcessDeliveryReportsUseCase processDeliveryReportsUseCase;

    @Scheduled(fixedDelayString = "${yamshchik.reports.poll-interval}")
    public void processReports() {
        try {
            processDeliveryReportsUseCase.processReports();
        } catch (RuntimeException e) {
            // Недоступный ящик не должен останавливать следующие чтения и проверку инцидентов
            log.error("Delivery report processing failed:", e);
        }
        processDeliveryReportsUseCase.raiseIncidents();
    }
}
