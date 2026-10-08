package ru.yamshchik.yamshchik.application.port.in;

import ru.yamshchik.yamshchik.domain.EmailId;
import ru.yamshchik.yamshchik.domain.EmailState;

import java.util.Optional;


public interface GetEmailStateUseCase {

    Optional<EmailState> getState(EmailId id);
}
