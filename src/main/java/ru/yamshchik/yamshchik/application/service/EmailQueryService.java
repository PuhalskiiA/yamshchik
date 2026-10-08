package ru.yamshchik.yamshchik.application.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.yamshchik.yamshchik.application.port.in.GetEmailStateUseCase;
import ru.yamshchik.yamshchik.application.port.out.EmailRepository;
import ru.yamshchik.yamshchik.domain.EmailId;
import ru.yamshchik.yamshchik.domain.EmailState;

import java.util.Optional;


@Service
@RequiredArgsConstructor
public class EmailQueryService implements GetEmailStateUseCase {

    private final EmailRepository emailRepository;

    @Override
    public Optional<EmailState> getState(EmailId id) {
        return emailRepository.findState(id);
    }
}
