package ru.yamshchik.yamshchik.domain;

import java.util.Objects;


/**
 * Содержимое вложения в хранилище. Одно содержимое может принадлежать нескольким письмам одного запроса.
 *
 * @param emailId одно из писем, которым принадлежит содержимое, — для записи в лог
 */
public record StoredAttachment(Attachment attachment, EmailId emailId) {

    public StoredAttachment {
        Objects.requireNonNull(attachment, "attachment");
        Objects.requireNonNull(emailId, "emailId");
    }
}
