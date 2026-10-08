package ru.yamshchik.yamshchik.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.yamshchik.yamshchik.application.port.in.SubmitEmailUseCase;
import ru.yamshchik.yamshchik.application.port.out.AttachmentStorage;
import ru.yamshchik.yamshchik.application.port.out.DispatchQueue;
import ru.yamshchik.yamshchik.application.port.out.EmailMetrics;
import ru.yamshchik.yamshchik.application.port.out.EmailRepository;
import ru.yamshchik.yamshchik.application.port.out.EmailTransport;
import ru.yamshchik.yamshchik.config.exception.type.EmailTooLargeException;
import ru.yamshchik.yamshchik.config.exception.type.ServiceException;
import ru.yamshchik.yamshchik.config.exception.type.ServiceValidationException;
import ru.yamshchik.yamshchik.config.properties.IntakeProperties;
import ru.yamshchik.yamshchik.config.properties.YamshchikProperties;
import ru.yamshchik.yamshchik.domain.Attachment;
import ru.yamshchik.yamshchik.domain.AttachmentContent;
import ru.yamshchik.yamshchik.domain.BodyAlternative;
import ru.yamshchik.yamshchik.domain.Email;
import ru.yamshchik.yamshchik.domain.EmailId;
import ru.yamshchik.yamshchik.domain.EmailMessage;
import ru.yamshchik.yamshchik.domain.EmailState;
import ru.yamshchik.yamshchik.domain.Recipients;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;


@Service
@RequiredArgsConstructor
@Slf4j
public class EmailSubmissionService implements SubmitEmailUseCase {

    private static final String STORAGE_KEY_FORMAT = "emails/%s/%d";

    private static final String DIGEST_ALGORITHM = "SHA-256";

    private static final int READ_BUFFER_SIZE = 8192;

    private static final char ADDRESS_SEPARATOR = '@';

    private static final int BASE64_INPUT_BLOCK = 3;

    private static final int BASE64_OUTPUT_BLOCK = 4;

    private static final int BASE64_LINE_LENGTH = 76;

    private static final int LINE_BREAK_LENGTH = 2;

    private final EmailRepository emailRepository;

    private final AttachmentStorage attachmentStorage;

    private final DispatchQueue dispatchQueue;

    private final EmailTransport emailTransport;

    private final YamshchikProperties properties;

    private final EmailMetrics metrics;

    private final Clock clock;

    @Override
    public EmailState submit(SubmitEmailCommand command) {
        List<AttachmentUpload> uploads = command.attachments();
        checkLimits(command.message(), uploads);

        EmailState state = EmailState.accepted(EmailId.newId(), clock.instant());
        Email email = new Email(state, command.message(), toAttachments(state.id(), uploads));
        emailTransport.verify(email);

        store(email, uploads);
        dispatchQueue.enqueue(state.id(), state.availableAt());
        metrics.accepted();
        return state;
    }

    private void checkLimits(EmailMessage message, List<AttachmentUpload> uploads) {
        IntakeProperties limits = properties.getIntake();
        Recipients recipients = message.recipients();
        int recipientCount = recipients.to().size() + recipients.cc().size() + recipients.bcc().size();
        if (recipientCount > limits.getMaxRecipients()) {
            throw new ServiceValidationException(
                    "Too many recipients: %d, allowed %d".formatted(recipientCount, limits.getMaxRecipients()));
        }
        if (uploads.size() > limits.getMaxAttachments()) {
            throw new ServiceValidationException(
                    "Too many attachments: %d, allowed %d".formatted(uploads.size(), limits.getMaxAttachments()));
        }

        String address = message.from().address();
        String senderDomain = address.substring(address.lastIndexOf(ADDRESS_SEPARATOR) + 1);
        List<String> allowedDomains = limits.getAllowedSenderDomains();
        if (!allowedDomains.isEmpty() && allowedDomains.stream().noneMatch(senderDomain::equalsIgnoreCase)) {
            throw new ServiceValidationException("Sender domain is not allowed: " + senderDomain);
        }

        long encodedSize = estimateEncodedSize(message, uploads);
        long maxSize = limits.getMaxMessageSize().toBytes();
        if (encodedSize > maxSize) {
            throw new EmailTooLargeException(
                    "Email is too large: about %d bytes after encoding, allowed %d".formatted(encodedSize, maxSize));
        }
    }

    // Оценка сверху по телу и вложениям: всё считается как base64, заголовки не учитываются
    private static long estimateEncodedSize(EmailMessage message, List<AttachmentUpload> uploads) {
        long size = 0;
        for (BodyAlternative alternative : message.body()) {
            size += base64Size(alternative.content().getBytes(StandardCharsets.UTF_8).length);
        }
        for (AttachmentUpload upload : uploads) {
            size += base64Size(upload.size());
        }
        return size;
    }

    private static long base64Size(long bytes) {
        long encoded = (bytes + BASE64_INPUT_BLOCK - 1) / BASE64_INPUT_BLOCK * BASE64_OUTPUT_BLOCK;
        return encoded + encoded / BASE64_LINE_LENGTH * LINE_BREAK_LENGTH;
    }

    private static List<Attachment> toAttachments(EmailId emailId, List<AttachmentUpload> uploads) {
        List<Attachment> attachments = new ArrayList<>();
        for (int position = 0; position < uploads.size(); position++) {
            AttachmentUpload upload = uploads.get(position);
            attachments.add(new Attachment(upload.filename(),
                                           upload.mediaType(),
                                           upload.disposition(),
                                           upload.contentId(),
                                           STORAGE_KEY_FORMAT.formatted(emailId.value(), position),
                                           upload.size(),
                                           sha256(upload.content())));
        }
        return attachments;
    }

    private static String sha256(AttachmentContent content) {
        try (InputStream input = content.open()) {
            MessageDigest digest = MessageDigest.getInstance(DIGEST_ALGORITHM);
            byte[] buffer = new byte[READ_BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException e) {
            throw new ServiceException("Failed to read attachment content", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // Сначала содержимое, потом запись о письме: письмо без вложений в хранилище в очередь попасть не должно
    private void store(Email email, List<AttachmentUpload> uploads) {
        List<Attachment> stored = new ArrayList<>();
        try {
            for (int position = 0; position < uploads.size(); position++) {
                Attachment attachment = email.attachments().get(position);
                String storageKey = attachmentStorage.store(attachment, uploads.get(position).content());
                stored.add(attachment.withStorageKey(storageKey));
            }
            emailRepository.save(new Email(email.state(), email.message(), stored));
        } catch (RuntimeException e) {
            stored.forEach(this::deleteQuietly);
            throw e;
        }
    }

    private void deleteQuietly(Attachment attachment) {
        try {
            attachmentStorage.delete(attachment);
        } catch (RuntimeException e) {
            log.warn("Attachment content {} was not removed after failed submission", attachment.storageKey(), e);
        }
    }
}
