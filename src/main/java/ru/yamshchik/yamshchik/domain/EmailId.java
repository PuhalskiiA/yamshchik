package ru.yamshchik.yamshchik.domain;

import java.util.Objects;
import java.util.UUID;


public record EmailId(UUID value) {

    public EmailId {
        Objects.requireNonNull(value, "value");
    }

    public static EmailId newId() {
        return new EmailId(UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
