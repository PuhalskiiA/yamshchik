package ru.yamshchik.yamshchik.application.port.in;

import ru.yamshchik.yamshchik.domain.EmailId;

import java.util.Optional;


public interface GetEmailStateUseCase {

    Optional<EmailView> getState(EmailId id);
}
