package ru.yamshchik.yamshchik.domain;

import ru.yamshchik.yamshchik.config.exception.type.ServiceValidationException;

import java.util.List;
import java.util.stream.Stream;


public record Recipients(List<Mailbox> to, List<Mailbox> cc, List<Mailbox> bcc) {

    public Recipients {
        to = to == null ? List.of() : List.copyOf(to);
        cc = cc == null ? List.of() : List.copyOf(cc);
        bcc = bcc == null ? List.of() : List.copyOf(bcc);
        if (to.isEmpty() && cc.isEmpty() && bcc.isEmpty()) {
            throw new ServiceValidationException("At least one recipient is required");
        }
    }

    /**
     * Адреса всех получателей, включая копии и скрытые копии.
     */
    public List<String> addresses() {
        return Stream.of(to, cc, bcc).flatMap(List::stream).map(Mailbox::address).toList();
    }
}
