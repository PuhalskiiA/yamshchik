package ru.yamshchik.yamshchik.adapter.out.persistence;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.yamshchik.yamshchik.adapter.out.persistence.EmailEntityRepository.EmailStateView;
import ru.yamshchik.yamshchik.domain.Attachment;
import ru.yamshchik.yamshchik.domain.Email;
import ru.yamshchik.yamshchik.domain.EmailId;
import ru.yamshchik.yamshchik.domain.EmailMessage;
import ru.yamshchik.yamshchik.domain.EmailState;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;


@Component
@RequiredArgsConstructor
class EmailEntityMapper {

    private final JsonMapper jsonMapper;

    EmailEntity toEntity(Email email) {
        EmailEntity entity = new EmailEntity();
        entity.setId(email.state().id().value());
        entity.setCreatedAt(email.state().createdAt());
        applyState(entity, email.state());
        entity.setMessage(jsonMapper.writeValueAsString(email.message()));

        List<Attachment> attachments = email.attachments();
        for (int position = 0; position < attachments.size(); position++) {
            entity.getAttachments().add(toAttachmentEntity(entity, position, attachments.get(position)));
        }
        return entity;
    }

    /**
     * Читает вложения, поэтому вызывается только внутри транзакции.
     */
    Email toEmail(EmailEntity entity) {
        return new Email(toState(entity),
                         jsonMapper.readValue(entity.getMessage(), EmailMessage.class),
                         toAttachments(entity.getAttachments()));
    }

    EmailState toState(EmailEntity entity) {
        return new EmailState(new EmailId(entity.getId()),
                              entity.getStatus(),
                              entity.getAttempts(),
                              entity.getLastError(),
                              entity.getCreatedAt(),
                              entity.getUpdatedAt(),
                              entity.getSentAt(),
                              entity.getAvailableAt());
    }

    EmailState toState(EmailStateView view) {
        return new EmailState(new EmailId(view.getId()),
                              view.getStatus(),
                              view.getAttempts(),
                              view.getLastError(),
                              view.getCreatedAt(),
                              view.getUpdatedAt(),
                              view.getSentAt(),
                              view.getAvailableAt());
    }

    void applyState(EmailEntity entity, EmailState state) {
        entity.setStatus(state.status());
        entity.setAttempts(state.attempts());
        entity.setLastError(state.lastError());
        entity.setUpdatedAt(state.updatedAt());
        entity.setSentAt(state.sentAt());
        entity.setAvailableAt(state.availableAt());
    }

    private static EmailAttachmentEntity toAttachmentEntity(EmailEntity email, int position, Attachment attachment) {
        EmailAttachmentEntity entity = new EmailAttachmentEntity();
        entity.setEmail(email);
        entity.setPosition(position);
        entity.setFilename(attachment.filename());
        entity.setMediaType(attachment.mediaType());
        entity.setDisposition(attachment.disposition());
        entity.setContentId(attachment.contentId());
        entity.setStorageKey(attachment.storageKey());
        entity.setSizeBytes(attachment.size());
        entity.setSha256(attachment.sha256());
        return entity;
    }

    private static List<Attachment> toAttachments(List<EmailAttachmentEntity> entities) {
        return entities.stream()
                .map(entity -> new Attachment(entity.getFilename(),
                                              entity.getMediaType(),
                                              entity.getDisposition(),
                                              entity.getContentId(),
                                              entity.getStorageKey(),
                                              entity.getSizeBytes(),
                                              entity.getSha256()))
                .toList();
    }
}
