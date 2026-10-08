package ru.yamshchik.yamshchik.adapter.in.rest;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import ru.yamshchik.yamshchik.adapter.in.rest.dto.AttachmentOptionsDto;
import ru.yamshchik.yamshchik.adapter.in.rest.dto.BodyDto;
import ru.yamshchik.yamshchik.adapter.in.rest.dto.EmailRequestDto;
import ru.yamshchik.yamshchik.adapter.in.rest.dto.EmailStateDto;
import ru.yamshchik.yamshchik.adapter.in.rest.dto.EmailStatusDto;
import ru.yamshchik.yamshchik.adapter.in.rest.dto.MailboxDto;
import ru.yamshchik.yamshchik.application.port.in.SubmitEmailUseCase.AttachmentUpload;
import ru.yamshchik.yamshchik.application.port.in.SubmitEmailUseCase.SubmitEmailCommand;
import ru.yamshchik.yamshchik.config.exception.type.ServiceValidationException;
import ru.yamshchik.yamshchik.domain.AttachmentDisposition;
import ru.yamshchik.yamshchik.domain.BodyAlternative;
import ru.yamshchik.yamshchik.domain.EmailMessage;
import ru.yamshchik.yamshchik.domain.EmailState;
import ru.yamshchik.yamshchik.domain.EmailStatus;
import ru.yamshchik.yamshchik.domain.Mailbox;
import ru.yamshchik.yamshchik.domain.Priority;
import ru.yamshchik.yamshchik.domain.Recipients;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


@Component
public class EmailRestMapper {

    public SubmitEmailCommand toCommand(EmailRequestDto request, List<MultipartFile> files) {
        EmailMessage message = new EmailMessage(
                toMailbox(request.getFrom()),
                toMailbox(request.getReplyTo()),
                new Recipients(toMailboxes(request.getTo()),
                               toMailboxes(request.getCc()),
                               toMailboxes(request.getBcc())),
                request.getSubject(),
                toBody(request.getBody()),
                request.getPriority() == null ? null : Priority.valueOf(request.getPriority().name()),
                request.getHeaders());

        return new SubmitEmailCommand(message, toAttachments(files, request.getAttachmentOptions()));
    }

    public EmailStateDto toDto(EmailState state) {
        EmailStateDto dto = new EmailStateDto();
        dto.setId(state.id().value());
        dto.setStatus(EmailStatusDto.valueOf(state.status().name()));
        dto.setAttempts(state.attempts());
        dto.setLastError(state.lastError());
        dto.setCreatedAt(toOffsetDateTime(state.createdAt()));
        dto.setUpdatedAt(toOffsetDateTime(state.updatedAt()));
        dto.setSentAt(toOffsetDateTime(state.sentAt()));
        if (state.status() == EmailStatus.DEFERRED) {
            dto.setNextAttemptAt(toOffsetDateTime(state.availableAt()));
        }
        return dto;
    }

    // Порядок вариантов — по возрастанию предпочтительности: HTML после текста
    private static List<BodyAlternative> toBody(BodyDto body) {
        List<BodyAlternative> alternatives = new ArrayList<>();
        if (body != null && body.getText() != null) {
            alternatives.add(BodyAlternative.plainText(body.getText()));
        }
        if (body != null && body.getHtml() != null) {
            alternatives.add(BodyAlternative.html(body.getHtml()));
        }
        return alternatives;
    }

    private static List<AttachmentUpload> toAttachments(List<MultipartFile> files, List<AttachmentOptionsDto> options) {
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

    private static AttachmentUpload toAttachment(MultipartFile file, AttachmentOptionsDto options) {
        boolean inline = options != null && options.getDisposition() != null
                && AttachmentDisposition.INLINE.name().equals(options.getDisposition().name());
        String mediaType = file.getContentType() == null
                ? MediaType.APPLICATION_OCTET_STREAM_VALUE
                : file.getContentType();
        return new AttachmentUpload(
                file.getOriginalFilename(),
                mediaType,
                inline ? AttachmentDisposition.INLINE : AttachmentDisposition.ATTACHMENT,
                options == null ? null : options.getContentId(),
                file.getSize(),
                file::getInputStream);
    }

    private static List<Mailbox> toMailboxes(List<MailboxDto> mailboxes) {
        return mailboxes == null ? List.of() : mailboxes.stream().map(EmailRestMapper::toMailbox).toList();
    }

    private static Mailbox toMailbox(MailboxDto mailbox) {
        return mailbox == null ? null : new Mailbox(mailbox.getAddress(), mailbox.getName());
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
