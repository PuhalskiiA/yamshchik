package ru.yamshchik.yamshchik.application.port.in;

import java.time.Duration;


/**
 * Отправка писем из очереди.
 */
public interface DispatchEmailsUseCase {

    /**
     * Берёт из очереди одно письмо и отправляет его, если темп отправки это позволяет.
     */
    DispatchResult dispatchNext();

    /**
     * @param rateLimitedFor через сколько темп позволит отправить следующее письмо; null — темп ни при чём:
     *                       письмо отправлено или готовых к отправке писем нет
     */
    record DispatchResult(boolean dispatched, Duration rateLimitedFor) {

        public static final DispatchResult DISPATCHED = new DispatchResult(true, null);

        public static final DispatchResult IDLE = new DispatchResult(false, null);

        public static DispatchResult rateLimited(Duration wait) {
            return new DispatchResult(false, wait);
        }
    }
}
