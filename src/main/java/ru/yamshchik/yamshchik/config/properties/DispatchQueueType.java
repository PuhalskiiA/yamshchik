package ru.yamshchik.yamshchik.config.properties;


public enum DispatchQueueType {

    /**
     * Очередь в таблице писем PostgreSQL.
     */
    POSTGRES,

    /**
     * Очередь в памяти процесса.
     */
    MEMORY
}
