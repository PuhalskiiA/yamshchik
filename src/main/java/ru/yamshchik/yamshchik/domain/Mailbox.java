package ru.yamshchik.yamshchik.domain;

import ru.yamshchik.yamshchik.config.exception.type.ServiceValidationException;


/**
 * Почтовый адрес с необязательным отображаемым именем.
 */
public record Mailbox(String address, String name) {

    private static final char ADDRESS_SEPARATOR = '@';

    public Mailbox {
        if (address == null || address.isBlank() || address.indexOf(ADDRESS_SEPARATOR) < 1) {
            throw new ServiceValidationException("Mailbox address is invalid: " + address);
        }
        HeaderRules.requireSingleLine("mailbox address", address);
        HeaderRules.requireSingleLine("mailbox name", name);
    }
}
