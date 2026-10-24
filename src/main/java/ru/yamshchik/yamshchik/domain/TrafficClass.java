package ru.yamshchik.yamshchik.domain;

/**
 * Класс трафика: между классами регулятор делит пропускную способность почтового сервера.
 */
public enum TrafficClass {

    /**
     * Срочные письма: им гарантирована доля темпа, и из очереди они берутся первыми.
     */
    URGENT,

    /**
     * Массовые письма: занимают остальной темп, а долю срочных — только пока срочных нет.
     */
    BULK;

    public static TrafficClass of(Priority priority) {
        return priority == Priority.HIGH ? URGENT : BULK;
    }
}
