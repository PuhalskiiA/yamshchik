package ru.yamshchik.yamshchik.application.port.in;

import ru.yamshchik.yamshchik.domain.Delivery;
import ru.yamshchik.yamshchik.domain.EmailState;

import java.util.List;


/**
 * Состояние письма вместе с доставками его получателям.
 */
public record EmailView(EmailState state, List<Delivery> deliveries) {

    public EmailView {
        deliveries = List.copyOf(deliveries);
    }
}
