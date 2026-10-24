package ru.yamshchik.yamshchik.adapter.in.rest;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.Named;
import org.mapstruct.ReportingPolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;
import ru.yamshchik.yamshchik.adapter.in.rest.dto.AttachmentDispositionDto;
import ru.yamshchik.yamshchik.adapter.in.rest.dto.AttachmentOptionsDto;
import ru.yamshchik.yamshchik.adapter.in.rest.dto.BodyDto;
import ru.yamshchik.yamshchik.adapter.in.rest.dto.DeliveryDto;
import ru.yamshchik.yamshchik.adapter.in.rest.dto.EmailRequestDto;
import ru.yamshchik.yamshchik.adapter.in.rest.dto.EmailStateDto;
import ru.yamshchik.yamshchik.adapter.in.rest.dto.EmailSubmissionDto;
import ru.yamshchik.yamshchik.application.port.in.EmailView;
import ru.yamshchik.yamshchik.application.port.in.SubmitEmailUseCase.AttachmentUpload;
import ru.yamshchik.yamshchik.application.port.in.SubmitEmailUseCase.SubmitEmailCommand;
import ru.yamshchik.yamshchik.config.exception.type.ServiceValidationException;
import ru.yamshchik.yamshchik.config.properties.SenderProperties;
import ru.yamshchik.yamshchik.config.properties.YamshchikProperties;
import ru.yamshchik.yamshchik.domain.AttachmentContent;
import ru.yamshchik.yamshchik.domain.AttachmentDisposition;
import ru.yamshchik.yamshchik.domain.BodyAlternative;
import ru.yamshchik.yamshchik.domain.Delivery;
import ru.yamshchik.yamshchik.domain.EmailMessage;
import ru.yamshchik.yamshchik.domain.EmailState;
import ru.yamshchik.yamshchik.domain.EmailStatus;
import ru.yamshchik.yamshchik.domain.Mailbox;
import ru.yamshchik.yamshchik.domain.Recipients;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;


@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, unmappedTargetPolicy = ReportingPolicy.ERROR)
public abstract class EmailRestMapper {

    private static final String NEXT_ATTEMPT_AT = "nextAttemptAt";

    private static final String SENDER = "sender";

    private static final String BATCH_ID = "batchId";

    private static final String DUPLICATE_FILE = "File %s is uploaded more than once";

    private static final String DUPLICATE_OPTIONS = "attachmentOptions describe file %s more than once";

    private static final String UNDESCRIBED_FILES = "Uploaded files are not described in attachmentOptions: %s";

    @Autowired
    protected YamshchikProperties properties;

    public SubmitEmailCommand toCommand(EmailRequestDto request, List<MultipartFile> files) {
        return new SubmitEmailCommand(toMessage(request),
                                      toAttachments(files, request.getAttachmentOptions()),
                                      Boolean.TRUE.equals(request.getPersonal()));
    }

    public EmailSubmissionDto toSubmissionDto(List<EmailView> views) {
        return new EmailSubmissionDto().emails(toDtos(views));
    }

    protected abstract List<EmailStateDto> toDtos(List<EmailView> views);

    @Mapping(target = "id", source = "state.id.value")
    @Mapping(target = "status", source = "state.status")
    @Mapping(target = "attempts", source = "state.attempts")
    @Mapping(target = "lastError", source = "state.lastError")
    @Mapping(target = "createdAt", source = "state.createdAt")
    @Mapping(target = "updatedAt", source = "state.updatedAt")
    @Mapping(target = "sentAt", source = "state.sentAt")
    @Mapping(target = NEXT_ATTEMPT_AT, source = "state", qualifiedByName = NEXT_ATTEMPT_AT)
    @Mapping(target = "batchId", source = "deliveries", qualifiedByName = BATCH_ID)
    public abstract EmailStateDto toDto(EmailView view);

    protected abstract DeliveryDto toDto(Delivery delivery);

    @Mapping(target = "from", source = ".", qualifiedByName = SENDER)
    @Mapping(target = "recipients", source = ".")
    protected abstract EmailMessage toMessage(EmailRequestDto request);

    // Отправитель у всех писем один — учётная запись сервиса, клиент его не задаёт
    @Named(SENDER)
    protected Mailbox toSender(EmailRequestDto request) {
        SenderProperties sender = properties.getSender();
        return new Mailbox(sender.getAddress(), sender.getName());
    }

    protected abstract Recipients toRecipients(EmailRequestDto request);

    // Файла может не быть — тогда вложение берётся из ранее принятых по контрольной сумме
    @Mapping(target = "filename", source = "options.filename")
    @Mapping(target = "mediaType", expression = "java(toMediaType(file))")
    @Mapping(target = "disposition", expression = "java(toDisposition(options))")
    @Mapping(target = "contentId", source = "options.contentId")
    @Mapping(target = "sha256", source = "options.sha256")
    @Mapping(target = "size", expression = "java(file == null ? 0 : file.getSize())")
    @Mapping(target = "content", expression = "java(toContent(file))")
    protected abstract AttachmentUpload toAttachment(AttachmentOptionsDto options, MultipartFile file);

    // Порядок вариантов — по возрастанию предпочтительности: HTML после текста
    protected List<BodyAlternative> toBody(BodyDto body) {
        List<BodyAlternative> alternatives = new ArrayList<>();
        if (body != null && body.getText() != null) {
            alternatives.add(BodyAlternative.plainText(body.getText()));
        }
        if (body != null && body.getHtml() != null) {
            alternatives.add(BodyAlternative.html(body.getHtml()));
        }
        return alternatives;
    }

    protected OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    // Пачка — свойство запроса, и у всех доставок письма она одна; получатель у письма есть всегда
    @Named(BATCH_ID)
    protected UUID toBatchId(List<Delivery> deliveries) {
        return deliveries.getFirst().batchId();
    }

    // Время следующей попытки есть только у отложенного письма
    @Named(NEXT_ATTEMPT_AT)
    protected OffsetDateTime toNextAttemptAt(EmailState state) {
        return state.status() == EmailStatus.DEFERRED ? toOffsetDateTime(state.availableAt()) : null;
    }

    // Файл без параметров в запросе отправляется обычным вложением
    protected AttachmentDisposition toDisposition(AttachmentOptionsDto options) {
        return options != null && options.getDisposition() == AttachmentDispositionDto.INLINE
                ? AttachmentDisposition.INLINE
                : AttachmentDisposition.ATTACHMENT;
    }

    protected AttachmentContent toContent(MultipartFile file) {
        return file == null ? null : file::getInputStream;
    }

    // Файл без типа отправляется как произвольные данные
    protected String toMediaType(MultipartFile file) {
        if (file == null) {
            return null;
        }
        return file.getContentType() == null ? MediaType.APPLICATION_OCTET_STREAM_VALUE : file.getContentType();
    }

    // Параметры вложений сопоставляются с файлами по имени: это не преобразование полей, а сверка двух списков
    // Вложения письма — это записи attachmentOptions; файл к записи находится по имени и может отсутствовать.
    // Это не преобразование полей, а сверка двух списков
    private List<AttachmentUpload> toAttachments(List<MultipartFile> files, List<AttachmentOptionsDto> options) {
        Map<String, MultipartFile> filesByName = new LinkedHashMap<>();
        if (files != null) {
            for (MultipartFile file : files) {
                if (filesByName.put(file.getOriginalFilename(), file) != null) {
                    throw new ServiceValidationException(DUPLICATE_FILE.formatted(file.getOriginalFilename()));
                }
            }
        }

        List<AttachmentUpload> attachments = new ArrayList<>();
        Set<String> described = new HashSet<>();
        if (options != null) {
            for (AttachmentOptionsDto option : options) {
                if (!described.add(option.getFilename())) {
                    throw new ServiceValidationException(DUPLICATE_OPTIONS.formatted(option.getFilename()));
                }
                attachments.add(toAttachment(option, filesByName.remove(option.getFilename())));
            }
        }
        if (!filesByName.isEmpty()) {
            throw new ServiceValidationException(UNDESCRIBED_FILES.formatted(filesByName.keySet()));
        }
        return attachments;
    }
}
