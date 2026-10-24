package ru.yamshchik.yamshchik.application.service.ratelimit;

import ru.yamshchik.yamshchik.domain.DeliveryFailure;

import java.util.List;


/**
 * Что исход отправки говорит о темпе: по этому сигналу адаптивная стратегия меняет темп.
 */
public enum SendSignal {

    /**
     * Сервер принял письмо.
     */
    ACCEPTED,

    /**
     * Сервер просит сбавить темп.
     */
    THROTTLED,

    /**
     * Отказ, не связанный с темпом: неверный адрес, недоступный сервер, сбой хранилища.
     */
    FAILED;

    private static final int SERVICE_NOT_AVAILABLE = 421;

    // RFC 3463: 4.7.x — временный отказ по политике сервера (в том числе по частоте), 4.4.2 — сервер закрыл
    // соединение (так Exchange отвечает на превышение лимита), 4.5.3 — слишком много получателей
    private static final List<String> THROTTLING_STATUSES = List.of("4.7.", "4.4.2", "4.5.3");

    public static SendSignal of(DeliveryFailure failure) {
        if (failure == null) {
            return ACCEPTED;
        }
        if (!failure.temporary()) {
            return FAILED;
        }
        Integer code = failure.replyCode();
        String status = failure.enhancedStatus();
        boolean throttling = (code != null && code == SERVICE_NOT_AVAILABLE)
                || (status != null && THROTTLING_STATUSES.stream().anyMatch(status::startsWith));
        return throttling ? THROTTLED : FAILED;
    }
}
