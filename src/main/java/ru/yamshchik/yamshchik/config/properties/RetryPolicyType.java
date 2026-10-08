package ru.yamshchik.yamshchik.config.properties;


public enum RetryPolicyType {

    /**
     * Повторяются только временные отказы, пауза растёт с каждой попыткой.
     */
    EXPONENTIAL,

    /**
     * Повторяется любой отказ через одну и ту же паузу.
     */
    FIXED
}
