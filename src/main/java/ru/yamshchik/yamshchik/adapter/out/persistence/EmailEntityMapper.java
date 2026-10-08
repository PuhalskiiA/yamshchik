package ru.yamshchik.yamshchik.adapter.out.persistence;

import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.MappingTarget;
import org.mapstruct.ReportingPolicy;
import org.springframework.beans.factory.annotation.Autowired;
import ru.yamshchik.yamshchik.adapter.out.persistence.EmailEntityRepository.EmailStateView;
import ru.yamshchik.yamshchik.domain.Attachment;
import ru.yamshchik.yamshchik.domain.Email;
import ru.yamshchik.yamshchik.domain.EmailId;
import ru.yamshchik.yamshchik.domain.EmailMessage;
import ru.yamshchik.yamshchik.domain.EmailState;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.UUID;


@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, unmappedTargetPolicy = ReportingPolicy.ERROR)
abstract class EmailEntityMapper {

    // Сгенерированный наследник создаётся конструктором без параметров, поэтому зависимость внедряется в поле
    @Autowired
    protected JsonMapper jsonMapper;

    @Mapping(target = "id", source = "state.id")
    @Mapping(target = "status", source = "state.status")
    @Mapping(target = "attempts", source = "state.attempts")
    @Mapping(target = "lastError", source = "state.lastError")
    @Mapping(target = "createdAt", source = "state.createdAt")
    @Mapping(target = "updatedAt", source = "state.updatedAt")
    @Mapping(target = "sentAt", source = "state.sentAt")
    @Mapping(target = "availableAt", source = "state.availableAt")
    abstract EmailEntity toEntity(Email email);

    /**
     * Читает вложения, поэтому вызывается только внутри транзакции.
     */
    @Mapping(target = "state", source = ".")
    abstract Email toEmail(EmailEntity entity);

    // sent(...) и withStorageKey(...) возвращают свой же тип, и MapStruct принимает их за свойства для записи
    @Mapping(target = "sent", ignore = true)
    abstract EmailState toState(EmailEntity entity);

    @Mapping(target = "sent", ignore = true)
    abstract EmailState toState(EmailStateView view);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "message", ignore = true)
    @Mapping(target = "attachments", ignore = true)
    abstract void applyState(@MappingTarget EmailEntity entity, EmailState state);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "email", ignore = true)
    @Mapping(target = "position", ignore = true)
    @Mapping(target = "sizeBytes", source = "size")
    abstract EmailAttachmentEntity toAttachmentEntity(Attachment attachment);

    @Mapping(target = "size", source = "sizeBytes")
    @Mapping(target = "withStorageKey", ignore = true)
    abstract Attachment toAttachment(EmailAttachmentEntity entity);

    // Вложение хранит ссылку на письмо и своё место в нём — ни того, ни другого в доменном вложении нет
    // Параметр email ограничивает вызов сборкой новой записи: при обновлении состояния вложения не трогаются
    @AfterMapping
    void linkAttachments(Email email, @MappingTarget EmailEntity entity) {
        List<EmailAttachmentEntity> attachments = entity.getAttachments();
        for (int position = 0; position < attachments.size(); position++) {
            attachments.get(position).setEmail(entity);
            attachments.get(position).setPosition(position);
        }
    }

    EmailId toEmailId(UUID id) {
        return new EmailId(id);
    }

    UUID toUuid(EmailId id) {
        return id.value();
    }

    String toJson(EmailMessage message) {
        return jsonMapper.writeValueAsString(message);
    }

    EmailMessage toMessage(String json) {
        return jsonMapper.readValue(json, EmailMessage.class);
    }
}
