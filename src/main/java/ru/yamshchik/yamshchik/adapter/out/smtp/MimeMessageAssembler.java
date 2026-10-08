package ru.yamshchik.yamshchik.adapter.out.smtp;

import jakarta.activation.DataHandler;
import jakarta.activation.DataSource;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Part;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.internet.MimePart;
import jakarta.mail.internet.MimeUtility;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.yamshchik.yamshchik.application.port.out.AttachmentStorage;
import ru.yamshchik.yamshchik.domain.Attachment;
import ru.yamshchik.yamshchik.domain.AttachmentDisposition;
import ru.yamshchik.yamshchik.domain.BodyAlternative;
import ru.yamshchik.yamshchik.domain.Email;
import ru.yamshchik.yamshchik.domain.EmailMessage;
import ru.yamshchik.yamshchik.domain.Mailbox;
import ru.yamshchik.yamshchik.domain.Priority;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;


/**
 * Собирает MIME-представление письма. Структура частей:
 * <pre>
 * multipart/mixed            — если есть обычные вложения
 *   multipart/related        — если есть встроенные вложения
 *     multipart/alternative  — если вариантов тела больше одного
 *       text/plain, text/html, ...
 *     встроенные вложения
 *   обычные вложения
 * </pre>
 * Уровень, которому нечего объединять, пропускается.
 */
@Component
@RequiredArgsConstructor
public class MimeMessageAssembler {

    public static final String EMAIL_ID_HEADER = "X-Yamshchik-Email-Id";

    private static final String CHARSET = StandardCharsets.UTF_8.name();

    private static final String SUBTYPE_MIXED = "mixed";

    private static final String SUBTYPE_RELATED = "related";

    private static final String SUBTYPE_ALTERNATIVE = "alternative";

    private static final String SUBTYPE_PLAIN = "plain";

    private static final String PRIORITY_HEADER = "X-Priority";

    private static final String IMPORTANCE_HEADER = "Importance";

    private static final String TRANSFER_ENCODING_HEADER = "Content-Transfer-Encoding";

    private static final String TRANSFER_ENCODING_BASE64 = "base64";

    private static final Map<Priority, String> PRIORITY_VALUES = Map.of(Priority.HIGH, "1", Priority.LOW, "5");

    private static final Map<Priority, String> IMPORTANCE_VALUES = Map.of(Priority.HIGH, "high", Priority.LOW, "low");

    private final AttachmentStorage attachmentStorage;

    public void assemble(Email email, MimeMessage target) throws MessagingException, UnsupportedEncodingException {
        EmailMessage message = email.message();

        target.setFrom(toAddress(message.from()));
        if (message.replyTo() != null) {
            target.setReplyTo(new InternetAddress[]{toAddress(message.replyTo())});
        }
        addRecipients(target, Message.RecipientType.TO, message.recipients().to());
        addRecipients(target, Message.RecipientType.CC, message.recipients().cc());
        addRecipients(target, Message.RecipientType.BCC, message.recipients().bcc());
        target.setSubject(message.subject(), CHARSET);

        for (Map.Entry<String, String> header : message.headers().entrySet()) {
            target.setHeader(header.getKey(), MimeUtility.encodeText(header.getValue(), CHARSET, null));
        }
        // По этому заголовку отчёт о доставке сопоставляется с исходным письмом
        target.setHeader(EMAIL_ID_HEADER, email.id().toString());
        if (message.priority() != Priority.NORMAL) {
            target.setHeader(PRIORITY_HEADER, PRIORITY_VALUES.get(message.priority()));
            target.setHeader(IMPORTANCE_HEADER, IMPORTANCE_VALUES.get(message.priority()));
        }

        List<Attachment> inline = filter(email.attachments(), AttachmentDisposition.INLINE);
        List<Attachment> regular = filter(email.attachments(), AttachmentDisposition.ATTACHMENT);
        fillMixed(target, message.body(), inline, regular);
        target.saveChanges();
    }

    private void fillMixed(
            MimePart target,
            List<BodyAlternative> body,
            List<Attachment> inline,
            List<Attachment> regular
    ) throws MessagingException, UnsupportedEncodingException {
        if (regular.isEmpty()) {
            fillRelated(target, body, inline);
            return;
        }
        MimeMultipart mixed = new MimeMultipart(SUBTYPE_MIXED);
        MimeBodyPart content = new MimeBodyPart();
        fillRelated(content, body, inline);
        mixed.addBodyPart(content);
        for (Attachment attachment : regular) {
            mixed.addBodyPart(toPart(attachment));
        }
        target.setContent(mixed);
    }

    private void fillRelated(
            MimePart target,
            List<BodyAlternative> body,
            List<Attachment> inline
    ) throws MessagingException, UnsupportedEncodingException {
        if (inline.isEmpty()) {
            fillBody(target, body);
            return;
        }
        MimeMultipart related = new MimeMultipart(SUBTYPE_RELATED);
        MimeBodyPart content = new MimeBodyPart();
        fillBody(content, body);
        related.addBodyPart(content);
        for (Attachment attachment : inline) {
            related.addBodyPart(toPart(attachment));
        }
        target.setContent(related);
    }

    private static void fillBody(MimePart target, List<BodyAlternative> body) throws MessagingException {
        if (body.isEmpty()) {
            // Письмо из одних вложений: тело обязано существовать, оставляем его пустым
            target.setText("", CHARSET, SUBTYPE_PLAIN);
            return;
        }
        if (body.size() == 1) {
            fillText(target, body.getFirst());
            return;
        }
        MimeMultipart alternative = new MimeMultipart(SUBTYPE_ALTERNATIVE);
        for (BodyAlternative bodyAlternative : body) {
            MimeBodyPart part = new MimeBodyPart();
            fillText(part, bodyAlternative);
            alternative.addBodyPart(part);
        }
        target.setContent(alternative);
    }

    private static void fillText(MimePart target, BodyAlternative alternative) throws MessagingException {
        String subtype = alternative.mediaType().substring(alternative.mediaType().indexOf('/') + 1);
        target.setText(alternative.content(), CHARSET, subtype);
    }

    private MimeBodyPart toPart(Attachment attachment) throws MessagingException, UnsupportedEncodingException {
        MimeBodyPart part = new MimeBodyPart();
        part.setDataHandler(new DataHandler(new StoredAttachmentDataSource(attachment, attachmentStorage)));
        // Без явной кодировки библиотека заранее читает содержимое, чтобы выбрать её сама
        part.setHeader(TRANSFER_ENCODING_HEADER, TRANSFER_ENCODING_BASE64);
        part.setFileName(MimeUtility.encodeText(attachment.filename(), CHARSET, null));
        if (attachment.disposition() == AttachmentDisposition.INLINE) {
            part.setDisposition(Part.INLINE);
            part.setContentID("<" + attachment.contentId() + ">");
        } else {
            part.setDisposition(Part.ATTACHMENT);
        }
        return part;
    }

    private static void addRecipients(
            MimeMessage target,
            Message.RecipientType type,
            List<Mailbox> mailboxes
    ) throws MessagingException, UnsupportedEncodingException {
        for (Mailbox mailbox : mailboxes) {
            target.addRecipient(type, toAddress(mailbox));
        }
    }

    private static InternetAddress toAddress(Mailbox mailbox) throws MessagingException, UnsupportedEncodingException {
        InternetAddress address = new InternetAddress(mailbox.address(), mailbox.name(), CHARSET);
        address.validate();
        return address;
    }

    private static List<Attachment> filter(List<Attachment> attachments, AttachmentDisposition disposition) {
        return attachments.stream()
                .filter(attachment -> attachment.disposition() == disposition)
                .toList();
    }

    /**
     * Содержимое вложения читается из хранилища потоком в момент передачи письма.
     */
    private record StoredAttachmentDataSource(Attachment attachment, AttachmentStorage storage) implements DataSource {

        @Override
        public InputStream getInputStream() {
            return storage.open(attachment);
        }

        @Override
        public OutputStream getOutputStream() {
            throw new UnsupportedOperationException("Attachment content is read-only");
        }

        @Override
        public String getContentType() {
            return attachment.mediaType();
        }

        @Override
        public String getName() {
            return attachment.filename();
        }
    }
}
