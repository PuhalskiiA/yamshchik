package ru.yamshchik.yamshchik.domain;

import ru.yamshchik.yamshchik.config.exception.type.ServiceValidationException;

import java.util.List;
import java.util.Objects;


/**
 * Письмо в очереди отправки: содержимое, вложения и текущее состояние.
 */
public record Email(EmailState state, EmailMessage message, List<Attachment> attachments) {

    public Email {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(message, "message");
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        if (message.body().isEmpty() && attachments.isEmpty()) {
            throw new ServiceValidationException("Email must have a body or an attachment");
        }
    }

    public EmailId id() {
        return state.id();
    }
}
