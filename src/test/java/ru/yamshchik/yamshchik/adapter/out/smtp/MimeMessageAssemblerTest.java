package ru.yamshchik.yamshchik.adapter.out.smtp;

import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.yamshchik.yamshchik.TestData;
import ru.yamshchik.yamshchik.application.port.out.AttachmentStorage;
import ru.yamshchik.yamshchik.domain.Attachment;
import ru.yamshchik.yamshchik.domain.BodyAlternative;
import ru.yamshchik.yamshchik.domain.Email;
import ru.yamshchik.yamshchik.domain.EmailId;
import ru.yamshchik.yamshchik.domain.EmailMessage;
import ru.yamshchik.yamshchik.domain.EmailState;
import ru.yamshchik.yamshchik.domain.Mailbox;
import ru.yamshchik.yamshchik.domain.Priority;
import ru.yamshchik.yamshchik.domain.Recipients;

import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static ru.yamshchik.yamshchik.TestData.NOW;
import static ru.yamshchik.yamshchik.TestData.mailbox;


@ExtendWith(MockitoExtension.class)
@DisplayName("Сборка MIME")
class MimeMessageAssemblerTest {

    private static final List<BodyAlternative> TEXT = List.of(BodyAlternative.plainText("текст"));

    private static final List<BodyAlternative> TEXT_AND_HTML =
            List.of(BodyAlternative.plainText("текст"), BodyAlternative.html("<p>html</p>"));

    @Mock
    private AttachmentStorage attachmentStorage;

    private MimeMessageAssembler assembler;

    @BeforeEach
    void setUp() {
        assembler = new MimeMessageAssembler(attachmentStorage);
    }

    @Test
    @DisplayName("Только текст — письмо без составных частей")
    void plainTextOnly() throws Exception {
        MimeMessage mime = assemble(email(TestData.message(TEXT), List.of()));

        assertThat(mime.isMimeType("text/plain"))
                .overridingErrorMessage("Письмо из одного текста не должно быть составным: %s", mime.getContentType())
                .isTrue();
        assertThat(mime.getContent()).isEqualTo("текст");
    }

    @Test
    @DisplayName("Текст и HTML — multipart/alternative, HTML последним")
    void alternative() throws Exception {
        MimeMessage mime = assemble(email(TestData.message(TEXT_AND_HTML), List.of()));

        MimeMultipart alternative = multipart(mime, "multipart/alternative");
        assertThat(alternative.getCount()).isEqualTo(2);
        assertThat(alternative.getBodyPart(0).isMimeType("text/plain")).isTrue();
        assertThat(alternative.getBodyPart(1).isMimeType("text/html"))
                .overridingErrorMessage("HTML должен идти последним: почтовые клиенты показывают последний вариант")
                .isTrue();
    }

    @Test
    @DisplayName("Обычные и встроенные вложения — mixed → related → alternative")
    void fullStructure() throws Exception {
        List<Attachment> attachments =
                List.of(TestData.attachment("отчёт.txt"), TestData.inlineAttachment("logo.png", "logo"));

        MimeMessage mime = assemble(email(TestData.message(TEXT_AND_HTML), attachments));

        MimeMultipart mixed = multipart(mime, "multipart/mixed");
        assertThat(mixed.getCount())
                .overridingErrorMessage("В mixed должны быть содержимое и одно обычное вложение")
                .isEqualTo(2);
        MimeMultipart related = multipart(mixed.getBodyPart(0), "multipart/related");
        assertThat(related.getCount()).isEqualTo(2);
        multipart(related.getBodyPart(0), "multipart/alternative");

        MimeBodyPart inline = (MimeBodyPart) related.getBodyPart(1);
        assertThat(inline.getDisposition()).isEqualTo(Part.INLINE);
        assertThat(inline.getContentID())
                .overridingErrorMessage("Встроенная картинка должна быть доступна по ссылке cid:logo")
                .isEqualTo("<logo>");

        MimeBodyPart regular = (MimeBodyPart) mixed.getBodyPart(1);
        assertThat(regular.getDisposition()).isEqualTo(Part.ATTACHMENT);
        assertThat(regular.getFileName())
                .overridingErrorMessage("Имя файла с кириллицей должно быть закодировано, а не искажено")
                .startsWith("=?UTF-8?");
        assertThat(regular.getEncoding()).isEqualTo("base64");
    }

    @Test
    @DisplayName("Письмо только из вложений получает пустое тело")
    void attachmentsOnly() throws Exception {
        MimeMessage mime = assemble(email(TestData.message(List.of()), List.of(TestData.attachment("a.txt"))));

        MimeMultipart mixed = multipart(mime, "multipart/mixed");
        assertThat(mixed.getBodyPart(0).isMimeType("text/plain")).isTrue();
        assertThat(mixed.getBodyPart(0).getContent()).isEqualTo("");
    }

    @Test
    @DisplayName("Сборка не читает содержимое вложений")
    void assemblyDoesNotReadAttachmentContent() throws Exception {
        assemble(email(TestData.message(TEXT), List.of(TestData.attachment("a.txt"))));

        verifyNoInteractions(attachmentStorage);
    }

    @Test
    @DisplayName("Адресаты, тема и пользовательские заголовки попадают в письмо")
    void headers() throws Exception {
        EmailMessage message = new EmailMessage(
                new Mailbox("noreply@example.org", "Ямщик"),
                mailbox("reply@example.org"),
                new Recipients(List.of(mailbox("to@example.org")), List.of(mailbox("cc@example.org")),
                               List.of(mailbox("bcc@example.org"))),
                "Тема письма", TEXT, Priority.HIGH, Map.of("X-Request-Id", "42"));

        MimeMessage mime = assemble(email(message, List.of()));

        assertThat(mime.getSubject()).isEqualTo("Тема письма");
        assertThat(((InternetAddress) mime.getFrom()[0]).getPersonal()).isEqualTo("Ямщик");
        assertThat(mime.getReplyTo()[0]).hasToString("reply@example.org");
        assertThat(mime.getRecipients(Message.RecipientType.TO)).hasSize(1);
        assertThat(mime.getRecipients(Message.RecipientType.CC)).hasSize(1);
        assertThat(mime.getRecipients(Message.RecipientType.BCC)).hasSize(1);
        assertThat(mime.getHeader("X-Request-Id", null)).isEqualTo("42");
        assertThat(mime.getHeader("X-Priority", null))
                .overridingErrorMessage("Высокий приоритет должен попадать в заголовок X-Priority как 1")
                .isEqualTo("1");
        assertThat(mime.getHeader("Importance", null)).isEqualTo("high");
    }

    @Test
    @DisplayName("Обычный приоритет заголовков приоритета не добавляет")
    void normalPriorityAddsNoHeaders() throws Exception {
        MimeMessage mime = assemble(email(TestData.message(TEXT), List.of()));

        assertThat(mime.getHeader("X-Priority")).isNull();
        assertThat(mime.getHeader("Importance")).isNull();
    }

    @Test
    @DisplayName("Идентификатор письма попадает в служебный заголовок и в Message-ID")
    void identifiers() throws Exception {
        Email email = email(TestData.message(TEXT), List.of());

        MimeMessage first = assemble(email);
        MimeMessage second = assemble(email);

        assertThat(first.getHeader(MimeMessageAssembler.EMAIL_ID_HEADER, null)).isEqualTo(email.id().toString());
        assertThat(first.getMessageID())
                .overridingErrorMessage("Message-ID должен строиться из идентификатора письма и домена отправителя")
                .isEqualTo("<" + email.id() + "@example.org>");
        assertThat(second.getMessageID())
                .overridingErrorMessage("При повторной сборке Message-ID должен остаться тем же")
                .isEqualTo(first.getMessageID());
    }

    @Test
    @DisplayName("Синтаксически неверный адрес не даёт собрать письмо")
    void invalidAddress() {
        EmailMessage message = new EmailMessage(
                mailbox("noreply@example.org"), null,
                new Recipients(List.of(mailbox("user@")), null, null), "Тема", TEXT, null, null);

        assertThatThrownBy(() -> assemble(email(message, List.of())))
                .overridingErrorMessage("Адрес без домена должен отклоняться при сборке, а не на почтовом сервере")
                .isInstanceOf(MessagingException.class);
    }

    private MimeMessage assemble(Email email) throws Exception {
        MimeMessage mime = new MimeMessage(Session.getInstance(new Properties()));
        assembler.assemble(email, mime);
        return mime;
    }

    private static Email email(EmailMessage message, List<Attachment> attachments) {
        return new Email(EmailState.accepted(EmailId.newId(), NOW), message, attachments);
    }

    private static MimeMultipart multipart(Part part, String expectedType) throws Exception {
        assertThat(part.isMimeType(expectedType))
                .overridingErrorMessage("Ожидалась часть %s, а получена %s", expectedType, part.getContentType())
                .isTrue();
        return (MimeMultipart) part.getContent();
    }
}
