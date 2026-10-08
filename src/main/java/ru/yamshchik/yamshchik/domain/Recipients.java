package ru.yamshchik.yamshchik.domain;

import ru.yamshchik.yamshchik.config.exception.type.ServiceValidationException;

import java.util.List;


public record Recipients(List<Mailbox> to, List<Mailbox> cc, List<Mailbox> bcc) {

    public Recipients {
        to = to == null ? List.of() : List.copyOf(to);
        cc = cc == null ? List.of() : List.copyOf(cc);
        bcc = bcc == null ? List.of() : List.copyOf(bcc);
        if (to.isEmpty() && cc.isEmpty() && bcc.isEmpty()) {
            throw new ServiceValidationException("At least one recipient is required");
        }
    }
}
