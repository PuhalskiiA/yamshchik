package ru.yamshchik.yamshchik.adapter.in.rest;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.Named;
import org.mapstruct.ReportingPolicy;
import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;
import ru.yamshchik.yamshchik.adapter.in.rest.dto.AttachmentDispositionDto;
import ru.yamshchik.yamshchik.adapter.in.rest.dto.AttachmentOptionsDto;
import ru.yamshchik.yamshchik.adapter.in.rest.dto.BodyDto;
import ru.yamshchik.yamshchik.adapter.in.rest.dto.EmailRequestDto;
import ru.yamshchik.yamshchik.adapter.in.rest.dto.EmailStateDto;
import ru.yamshchik.yamshchik.application.port.in.SubmitEmailUseCase.AttachmentUpload;
import ru.yamshchik.yamshchik.application.port.in.SubmitEmailUseCase.SubmitEmailCommand;
import ru.yamshchik.yamshchik.config.exception.type.ServiceValidationException;
import ru.yamshchik.yamshchik.domain.AttachmentContent;
import ru.yamshchik.yamshchik.domain.AttachmentDisposition;
import ru.yamshchik.yamshchik.domain.BodyAlternative;
import ru.yamshchik.yamshchik.domain.EmailMessage;
import ru.yamshchik.yamshchik.domain.EmailState;
import ru.yamshchik.yamshchik.domain.EmailStatus;
import ru.yamshchik.yamshchik.domain.Recipients;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, unmappedTargetPolicy = ReportingPolicy.ERROR)
public abstract class EmailRestMapper {

    private static final String NEXT_ATTEMPT_AT = "nextAttemptAt";

    public SubmitEmailCommand toCommand(EmailRequestDto request, List<MultipartFile> files) {
        return new SubmitEmailCommand(toMessage(request), toAttachments(files, request.getAttachmentOptions()));
    }

    @Mapping(target = "id", source = "id.value")
    @Mapping(target = NEXT_ATTEMPT_AT, source = "state", qualifiedByName = NEXT_ATTEMPT_AT)
    public abstract EmailStateDto toDto(EmailState state);

    @Mapping(target = "recipients", source = ".")
    protected abstract EmailMessage toMessage(EmailRequestDto request);

    protected abstract Recipients toRecipients(EmailRequestDto request);

    @Mapping(target = "filename", source = "file.originalFilename")
    @Mapping(target = "mediaType", source = "file.contentType",
             defaultValue = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @Mapping(target = "disposition", expression = "java(toDisposition(options))")
    @Mapping(target = "contentId", source = "options.contentId")
    @Mapping(target = "size", source = "file.size")
    @Mapping(target = "content", expression = "java(toContent(file))")
    protected abstract AttachmentUpload toAttachment(MultipartFile file, AttachmentOptionsDto options);

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
        return file::getInputStream;
    }

    // Параметры вложений сопоставляются с файлами по имени: это не преобразование полей, а сверка двух списков
    private List<AttachmentUpload> toAttachments(List<MultipartFile> files, List<AttachmentOptionsDto> options) {
        Map<String, AttachmentOptionsDto> optionsByFilename = new HashMap<>();
        if (options != null) {
            options.forEach(option -> optionsByFilename.put(option.getFilename(), option));
        }

        List<AttachmentUpload> attachments = new ArrayList<>();
        if (files != null) {
            for (MultipartFile file : files) {
                attachments.add(toAttachment(file, optionsByFilename.remove(file.getOriginalFilename())));
            }
        }
        if (!optionsByFilename.isEmpty()) {
            throw new ServiceValidationException(
                    "attachmentOptions refer to files that were not uploaded: " + optionsByFilename.keySet());
        }
        return attachments;
    }
}
