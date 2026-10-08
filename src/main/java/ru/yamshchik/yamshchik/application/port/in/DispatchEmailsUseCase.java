package ru.yamshchik.yamshchik.application.port.in;

/**
 * Отправка писем из очереди.
 */
public interface DispatchEmailsUseCase {

    /**
     * Берёт из очереди одно письмо и отправляет его.
     *
     * @return {@code false}, если очередь пуста
     */
    boolean dispatchNext();
}
