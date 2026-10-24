package ru.yamshchik.yamshchik.application.port.out;

import ru.yamshchik.yamshchik.domain.DeliveryReport;

import java.util.List;
import java.util.function.Predicate;


/**
 * Ящик, в который почтовый сервер присылает отчёты о доставке.
 */
public interface DeliveryReportSource {

    /**
     * Читает ещё не обработанные письма ящика и передаёт отчёты из каждого обработчику. Письмо считается
     * обработанным, если обработчик вернул true; иначе оно будет прочитано снова при следующем вызове.
     * Письма, в которых отчётов о наших письмах нет, считаются обработанными сразу.
     *
     * @param limit сколько писем ящика прочитать за вызов
     * @return сколько писем прочитано
     */
    int poll(int limit, Predicate<List<DeliveryReport>> handler);
}
