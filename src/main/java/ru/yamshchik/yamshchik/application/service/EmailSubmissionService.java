package ru.yamshchik.yamshchik.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.yamshchik.yamshchik.application.port.in.EmailView;
import ru.yamshchik.yamshchik.application.port.in.SubmitEmailUseCase;
import ru.yamshchik.yamshchik.application.port.out.AttachmentStorage;
import ru.yamshchik.yamshchik.application.port.out.DispatchQueue;
import ru.yamshchik.yamshchik.application.port.out.EmailMetrics;
import ru.yamshchik.yamshchik.application.port.out.EmailRepository;
import ru.yamshchik.yamshchik.application.port.out.EmailTransport;
import ru.yamshchik.yamshchik.application.port.out.StoredAttachments;
import ru.yamshchik.yamshchik.config.exception.type.EmailTooLargeException;
import ru.yamshchik.yamshchik.config.exception.type.MissingAttachmentsException;
import ru.yamshchik.yamshchik.config.exception.type.ServiceException;
import ru.yamshchik.yamshchik.config.exception.type.ServiceValidationException;
import ru.yamshchik.yamshchik.config.properties.IntakeProperties;
import ru.yamshchik.yamshchik.config.properties.YamshchikProperties;
import ru.yamshchik.yamshchik.domain.Attachment;
import ru.yamshchik.yamshchik.domain.AttachmentContent;
import ru.yamshchik.yamshchik.domain.BodyAlternative;
import ru.yamshchik.yamshchik.domain.Delivery;
import ru.yamshchik.yamshchik.domain.Email;
import ru.yamshchik.yamshchik.domain.EmailId;
import ru.yamshchik.yamshchik.domain.EmailMessage;
import ru.yamshchik.yamshchik.domain.EmailState;
import ru.yamshchik.yamshchik.domain.Recipients;
import ru.yamshchik.yamshchik.domain.TrafficClass;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;


@Service
@RequiredArgsConstructor
@Slf4j
public class EmailSubmissionService implements SubmitEmailUseCase {

    private static final String STORAGE_KEY_FORMAT = "emails/%s/%d";

    private static final String CHECKSUM_MISMATCH = "Attachment %s: sha256 does not match the file content";

    private static final String DIGEST_ALGORITHM = "SHA-256";

    private static final int READ_BUFFER_SIZE = 8192;

    private static final int BASE64_INPUT_BLOCK = 3;

    private static final int BASE64_OUTPUT_BLOCK = 4;

    private static final int BASE64_LINE_LENGTH = 76;

    private static final int LINE_BREAK_LENGTH = 2;

    private final EmailRepository emailRepository;

    private final AttachmentStorage attachmentStorage;

    private final StoredAttachments storedAttachments;

    private final DispatchQueue dispatchQueue;

    private final EmailTransport emailTransport;

    private final YamshchikProperties properties;

    private final EmailMetrics metrics;

    private final Clock clock;

    @Override
    public List<EmailView> submit(SubmitEmailCommand command) {
        List<AttachmentUpload> uploads = command.attachments();
        checkCounts(command.message(), uploads);
        Map<String, Attachment> stored = findStored(uploads);
        checkSize(command.message(), uploads, stored);
        List<EmailMessage> messages = command.personal()
                ? command.message().splitByRecipient()
                : List.of(command.message());

        Instant now = clock.instant();
        List<EmailState> states = messages.stream().map(message -> EmailState.accepted(EmailId.newId(), now)).toList();
        // Вложения у писем одного запроса общие: в хранилище они кладутся один раз, под ключом первого письма.
        // Вложение, которое уже лежит в хранилище, второй раз не кладётся — письмо ссылается на него
        List<Attachment> attachments = toAttachments(states.getFirst().id(), uploads, stored);

        List<Email> emails = new ArrayList<>();
        for (int index = 0; index < messages.size(); index++) {
            Email email = new Email(states.get(index), messages.get(index), attachments);
            emailTransport.verify(email);
            emails.add(email);
        }

        UUID batchId = UUID.randomUUID();
        List<EmailView> views = emails.stream()
                .map(email -> new EmailView(email.state(), toDeliveries(email, batchId, now)))
                .toList();

        store(emails, views.stream().flatMap(view -> view.deliveries().stream()).toList(), uploads, stored);
        TrafficClass trafficClass = TrafficClass.of(command.message().priority());
        for (EmailState state : states) {
            dispatchQueue.enqueue(state.id(), trafficClass, state.availableAt());
            metrics.accepted();
        }
        return views;
    }

    // Получатель, указанный в письме дважды, получает его один раз
    private static List<Delivery> toDeliveries(Email email, UUID batchId, Instant now) {
        return email.message().recipients().addresses().stream()
                .map(Delivery::recipientKey)
                .distinct()
                .map(recipient -> Delivery.pending(email.id(), batchId, recipient, email.message().priority(), now))
                .toList();
    }

    private void checkCounts(EmailMessage message, List<AttachmentUpload> uploads) {
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
    }

    /**
     * @return ранее принятые вложения по контрольной сумме — для тех вложений запроса, что уже есть в хранилище
     * @throws MissingAttachmentsException если файл не прислан, а такого вложения в хранилище нет
     */
    private Map<String, Attachment> findStored(List<AttachmentUpload> uploads) {
        Map<String, Attachment> stored = new HashMap<>();
        Set<String> missing = new LinkedHashSet<>();
        for (AttachmentUpload upload : uploads) {
            if (stored.containsKey(upload.sha256())) {
                continue;
            }
            Optional<Attachment> found = storedAttachments.findBySha256(upload.sha256());
            if (found.isPresent()) {
                stored.put(upload.sha256(), found.get());
            } else if (!upload.hasContent()) {
                missing.add(upload.sha256());
            }
        }
        if (!missing.isEmpty()) {
            throw new MissingAttachmentsException(List.copyOf(missing));
        }
        return stored;
    }

    private void checkSize(EmailMessage message, List<AttachmentUpload> uploads, Map<String, Attachment> stored) {
        long encodedSize = estimateEncodedSize(message, uploads, stored);
        long maxSize = properties.getIntake().getMaxMessageSize().toBytes();
        if (encodedSize > maxSize) {
            throw new EmailTooLargeException(
                    "Email is too large: about %d bytes after encoding, allowed %d".formatted(encodedSize, maxSize));
        }
    }

    // Оценка сверху по телу и вложениям: всё считается как base64, заголовки не учитываются
    private static long estimateEncodedSize(
            EmailMessage message,
            List<AttachmentUpload> uploads,
            Map<String, Attachment> stored
    ) {
        long size = 0;
        for (BodyAlternative alternative : message.body()) {
            size += base64Size(alternative.content().getBytes(StandardCharsets.UTF_8).length);
        }
        for (AttachmentUpload upload : uploads) {
            size += base64Size(upload.hasContent() ? upload.size() : stored.get(upload.sha256()).size());
        }
        return size;
    }

    private static long base64Size(long bytes) {
        long encoded = (bytes + BASE64_INPUT_BLOCK - 1) / BASE64_INPUT_BLOCK * BASE64_OUTPUT_BLOCK;
        return encoded + encoded / BASE64_LINE_LENGTH * LINE_BREAK_LENGTH;
    }

    // Присланный файл сверяется с заявленной суммой: по ней вложение потом найдут другие запросы
    private static List<Attachment> toAttachments(
            EmailId emailId,
            List<AttachmentUpload> uploads,
            Map<String, Attachment> stored
    ) {
        List<Attachment> attachments = new ArrayList<>();
        for (int position = 0; position < uploads.size(); position++) {
            AttachmentUpload upload = uploads.get(position);
            if (upload.hasContent() && !sha256(upload.content()).equals(upload.sha256())) {
                throw new ServiceValidationException(CHECKSUM_MISMATCH.formatted(upload.filename()));
            }
            Attachment existing = stored.get(upload.sha256());
            attachments.add(existing == null
                                    ? new Attachment(upload.filename(), upload.mediaType(), upload.disposition(),
                                                     upload.contentId(),
                                                     STORAGE_KEY_FORMAT.formatted(emailId.value(), position),
                                                     upload.size(), upload.sha256())
                                    : new Attachment(upload.filename(),
                                                     upload.hasContent() ? upload.mediaType() : existing.mediaType(),
                                                     upload.disposition(), upload.contentId(), existing.storageKey(),
                                                     existing.size(), upload.sha256()));
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

    // Сначала содержимое, потом запись о письме: письмо без вложений в хранилище в очередь попасть не должно.
    // При сбое удаляется только загруженное сейчас: ранее принятые вложения принадлежат и другим письмам
    private void store(
            List<Email> emails,
            List<Delivery> deliveries,
            List<AttachmentUpload> uploads,
            Map<String, Attachment> stored
    ) {
        List<Attachment> attachments = emails.getFirst().attachments();
        List<Attachment> result = new ArrayList<>();
        List<Attachment> uploaded = new ArrayList<>();
        Map<String, String> keysBySha256 = new HashMap<>();
        try {
            for (int position = 0; position < uploads.size(); position++) {
                Attachment attachment = attachments.get(position);
                AttachmentUpload upload = uploads.get(position);
                if (stored.containsKey(upload.sha256())) {
                    result.add(attachment);
                    continue;
                }
                // Один и тот же файл, приложенный к запросу дважды, кладётся в хранилище один раз
                String storageKey = keysBySha256.get(upload.sha256());
                if (storageKey == null) {
                    storageKey = attachmentStorage.store(attachment, upload.content());
                    keysBySha256.put(upload.sha256(), storageKey);
                    uploaded.add(attachment.withStorageKey(storageKey));
                }
                result.add(attachment.withStorageKey(storageKey));
            }
            emailRepository.saveAll(emails.stream()
                                            .map(email -> new Email(email.state(), email.message(), result))
                                            .toList(),
                                    deliveries);
        } catch (RuntimeException e) {
            uploaded.forEach(this::deleteQuietly);
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
