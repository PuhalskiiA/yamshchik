package ru.yamshchik.yamshchik.adapter.out.persistence;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import ru.yamshchik.yamshchik.application.port.out.EmailRepository;
import ru.yamshchik.yamshchik.domain.Email;
import ru.yamshchik.yamshchik.domain.EmailId;
import ru.yamshchik.yamshchik.domain.EmailState;
import ru.yamshchik.yamshchik.domain.EmailStatus;

import java.util.Optional;


@Repository
@RequiredArgsConstructor
public class JpaEmailRepository implements EmailRepository {

    private final EmailEntityRepository entityRepository;

    private final EmailEntityMapper mapper;

    @Override
    @Transactional
    public void save(Email email) {
        entityRepository.save(mapper.toEntity(email));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Email> find(EmailId id) {
        return entityRepository.findById(id.value()).map(mapper::toEmail);
    }

    @Override
    public Optional<EmailState> findState(EmailId id) {
        return entityRepository.findStateById(id.value()).map(mapper::toState);
    }

    @Override
    public long countByStatus(EmailStatus status) {
        return entityRepository.countByStatus(status);
    }

    @Override
    @Transactional
    public void updateState(EmailState state) {
        entityRepository.updateState(state.id().value(),
                                     state.status(),
                                     state.attempts(),
                                     state.lastError(),
                                     state.updatedAt(),
                                     state.sentAt(),
                                     state.availableAt());
    }

    @Override
    @Transactional
    public boolean saveAttemptResult(EmailState result) {
        return entityRepository.updateStateOfAttempt(result.id().value(),
                                                     EmailStatus.SENDING,
                                                     result.attempts(),
                                                     result.status(),
                                                     result.lastError(),
                                                     result.updatedAt(),
                                                     result.sentAt(),
                                                     result.availableAt()) > 0;
    }
}
