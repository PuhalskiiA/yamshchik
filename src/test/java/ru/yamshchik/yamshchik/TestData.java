package ru.yamshchik.yamshchik;

import ru.yamshchik.yamshchik.domain.Attachment;
import ru.yamshchik.yamshchik.domain.AttachmentDisposition;
import ru.yamshchik.yamshchik.domain.BodyAlternative;
import ru.yamshchik.yamshchik.domain.Email;
import ru.yamshchik.yamshchik.domain.EmailId;
import ru.yamshchik.yamshchik.domain.EmailMessage;
import ru.yamshchik.yamshchik.domain.EmailState;
import ru.yamshchik.yamshchik.domain.Mailbox;
import ru.yamshchik.yamshchik.domain.Recipients;

import java.time.Duration;
import java.time.Instant;
import java.util.List;


/**
 * Заготовки доменных объектов для тестов.
 */
public final class TestData {

    public static final Instant NOW = Instant.parse("2026-10-09T10:00:00Z");

    public static final Duration LEASE = Duration.ofMinutes(5);

    /**
     * SHA-256 от строки «abc».
     */
    public static final String SHA256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    private TestData() {
    }

    public static Mailbox mailbox(String address) {
        return new Mailbox(address, null);
    }

    public static EmailMessage message() {
        return message(List.of(BodyAlternative.plainText("текст")));
    }

    public static EmailMessage message(List<BodyAlternative> body) {
        return new EmailMessage(mailbox("noreply@example.org"),
                                null,
                                new Recipients(List.of(mailbox("user@example.org")), null, null),
                                "Тема",
                                body,
                                null,
                                null);
    }

    public static Attachment attachment(String filename) {
        return new Attachment(filename, "text/plain", AttachmentDisposition.ATTACHMENT, null,
                              "emails/key/" + filename, 3, SHA256);
    }

    public static Attachment inlineAttachment(String filename, String contentId) {
        return new Attachment(filename, "image/png", AttachmentDisposition.INLINE, contentId,
                              "emails/key/" + filename, 3, SHA256);
    }

    public static Email acceptedEmail() {
        return new Email(EmailState.accepted(EmailId.newId(), NOW), message(), List.of());
    }

    public static Email sendingEmail() {
        Email accepted = acceptedEmail();
        return new Email(accepted.state().startSending(NOW, LEASE), accepted.message(), accepted.attachments());
    }
}
