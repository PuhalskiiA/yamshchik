package ru.yamshchik.yamshchik.application.port.in;

/**
 * Обработка отчётов о доставке.
 */
public interface ProcessDeliveryReportsUseCase {

    /**
     * Читает новые отчёты из ящика учётной записи и применяет их к доставкам.
     *
     * @return сколько писем ящика прочитано
     */
    int processReports();

    /**
     * Заводит инциденты по важным письмам, отчёт о которых не пришёл вовремя.
     *
     * @return сколько инцидентов заведено
     */
    int raiseIncidents();
}
