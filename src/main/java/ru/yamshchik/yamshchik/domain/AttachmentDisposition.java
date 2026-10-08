package ru.yamshchik.yamshchik.domain;

public enum AttachmentDisposition {

    /**
     * Обычное вложение.
     */
    ATTACHMENT,

    /**
     * Встроено в HTML-тело и доступно по ссылке {@code cid:}.
     */
    INLINE
}
