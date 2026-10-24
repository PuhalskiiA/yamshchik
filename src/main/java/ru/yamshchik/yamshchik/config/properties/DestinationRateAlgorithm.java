package ru.yamshchik.yamshchik.config.properties;


public enum DestinationRateAlgorithm {

    /**
     * Темп растёт на шаг, пока сервер принимает, и падает в несколько раз, когда просит сбавить.
     */
    AIMD,

    /**
     * Темп держится по оценке пропускной способности сервера: сколько писем он реально принимает за окно.
     */
    ESTIMATE
}
