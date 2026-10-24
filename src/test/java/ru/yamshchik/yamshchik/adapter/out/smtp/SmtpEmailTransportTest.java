package ru.yamshchik.yamshchik.adapter.out.smtp;

import jakarta.mail.Address;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import ru.yamshchik.yamshchik.TestData;
import ru.yamshchik.yamshchik.application.port.out.AttachmentStorage;
import ru.yamshchik.yamshchik.config.exception.type.AttachmentStorageException;
import ru.yamshchik.yamshchik.config.exception.type.EmailTransportException;
import ru.yamshchik.yamshchik.config.exception.type.ServiceValidationException;
import ru.yamshchik.yamshchik.domain.BodyAlternative;
import ru.yamshchik.yamshchik.domain.DeliveryFailure;
import ru.yamshchik.yamshchik.domain.Email;
import ru.yamshchik.yamshchik.domain.EmailId;
import ru.yamshchik.yamshchik.domain.EmailMessage;
import ru.yamshchik.yamshchik.domain.EmailState;
import ru.yamshchik.yamshchik.domain.Recipients;

import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static ru.yamshchik.yamshchik.TestData.NOW;
import static ru.yamshchik.yamshchik.TestData.mailbox;


@ExtendWith(MockitoExtension.class)
@DisplayName("Передача письма почтовому серверу и разбор отказов")
class SmtpEmailTransportTest {

    private static final List<String> RECIPIENTS = List.of("user@example.org");

    @Mock
    private JavaMailSenderImpl mailSender;

    @Mock
    private AttachmentStorage attachmentStorage;

    private SmtpEmailTransport transport;

    @BeforeEach
    void setUp() {
        lenient().when(mailSender.createMimeMessage())
                .thenAnswer(call -> new MimeMessage(Session.getInstance(new Properties())));
        lenient().when(mailSender.getSession()).thenReturn(Session.getInstance(new Properties()));
        transport = new SmtpEmailTransport(mailSender, new MimeMessageAssembler(attachmentStorage));
    }

    @Test
    @DisplayName("Собранное письмо передаётся серверу")
    void sends() {
        transport.send(TestData.acceptedEmail(), RECIPIENTS);

        verify(mailSender).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("Сервер доставляет только указанным адресатам, в заголовках остаются все получатели")
    void envelopeDiffersFromHeaders() throws Exception {
        EmailMessage twoRecipients = new EmailMessage(mailbox("noreply@example.org"), null,
                                                      new Recipients(List.of(mailbox("first@example.org"),
                                                                             mailbox("second@example.org")),
                                                                     null, null),
                                                      "Тема", List.of(BodyAlternative.plainText("текст")),
                                                      null, null);
        EmailState sending = EmailState.accepted(EmailId.newId(), NOW).startSending(NOW, TestData.LEASE);

        transport.send(new Email(sending, twoRecipients, List.of()), List.of("second@example.org"));

        ArgumentCaptor<MimeMessage> sent = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(sent.capture());
        MimeMessage message = sent.getValue();
        assertThat(message.getAllRecipients()).extracting(Address::toString)
                .overridingErrorMessage("Повторная отправка должна уйти только тому, по кому пришёл отказ")
                .containsExactly("second@example.org");
        assertThat(message.getHeader("To", ","))
                .overridingErrorMessage("В заголовке письма должны остаться все получатели")
                .contains("first@example.org", "second@example.org");
        assertThat(message.getHeader(MimeMessageAssembler.ATTEMPT_HEADER, null))
                .overridingErrorMessage("По номеру попытки отсекаются отчёты о прошлых попытках")
                .isEqualTo("1");
    }

    @Test
    @DisplayName("Ответ 4xx — временный отказ с кодом и расширенным статусом")
    void temporaryReply() throws Exception {
        DeliveryFailure failure = failureOf(new MailSendException(Map.of(
                "message", new SMTPSendFailedException("DATA", 451, "451 4.7.1 Try again later",
                                                       null, null, null, null))));

        assertThat(failure.temporary())
                .overridingErrorMessage("Ответ 451 — временный отказ, письмо нужно повторить")
                .isTrue();
        assertThat(failure.replyCode()).isEqualTo(451);
        assertThat(failure.enhancedStatus())
                .overridingErrorMessage("Расширенный статус 4.7.1 должен извлекаться из текста ответа")
                .isEqualTo("4.7.1");
    }

    @Test
    @DisplayName("Ответ 5xx — постоянный отказ")
    void permanentReply() throws Exception {
        DeliveryFailure failure = failureOf(new MailSendException(Map.of(
                "message", new SMTPSendFailedException("DATA", 552, "552 5.3.4 Message too big",
                                                       null, null, null, null))));

        assertThat(failure.temporary())
                .overridingErrorMessage("Ответ 552 — постоянный отказ, повторять его бессмысленно")
                .isFalse();
        assertThat(failure.replyCode()).isEqualTo(552);
        assertThat(failure.enhancedStatus()).isEqualTo("5.3.4");
    }

    @Test
    @DisplayName("Отказ по одному из получателей с кодом 5xx делает отказ постоянным")
    void permanentRecipientFailureWins() throws Exception {
        SMTPAddressFailedException temporary = new SMTPAddressFailedException(
                new InternetAddress("slow@example.org"), "RCPT TO", 450, "450 4.2.1 Mailbox busy");
        SMTPAddressFailedException permanent = new SMTPAddressFailedException(
                new InternetAddress("gone@example.org"), "RCPT TO", 550, "550 5.1.1 No such user");
        temporary.setNextException(permanent);
        SendFailedException invalidAddresses = new SendFailedException("Invalid Addresses", temporary);

        DeliveryFailure failure = failureOf(new MailSendException(Map.of("message", invalidAddresses)));

        assertThat(failure.temporary())
                .overridingErrorMessage("Если хотя бы один получатель отвергнут навсегда, повтор не поможет")
                .isFalse();
        assertThat(failure.replyCode()).isEqualTo(550);
    }

    @Test
    @DisplayName("Сбой соединения — временный отказ без кода ответа")
    void connectionFailure() {
        DeliveryFailure failure = failureOf(
                new MailSendException("Mail server connection failed", new MessagingException("Connection refused")));

        assertThat(failure.temporary())
                .overridingErrorMessage("Недоступный сервер — повод повторить, а не потерять письмо")
                .isTrue();
        assertThat(failure.replyCode()).isNull();
        assertThat(failure.enhancedStatus()).isNull();
    }

    @Test
    @DisplayName("Ошибка входа на сервер — временный отказ")
    void authenticationFailure() {
        DeliveryFailure failure = failureOf(new MailAuthenticationException("535 Authentication failed"));

        assertThat(failure.temporary())
                .overridingErrorMessage("Из-за неверной настройки сервера письма не должны пропадать")
                .isTrue();
    }

    @Test
    @DisplayName("Пропавшее вложение — постоянный отказ, недоступное хранилище — временный")
    void storageFailures() {
        DeliveryFailure missing = failureOf(new MailSendException(Map.of(
                "message", new AttachmentStorageException("missing", false, null))));
        DeliveryFailure unavailable = failureOf(new MailSendException(Map.of(
                "message", new AttachmentStorageException("unavailable", true, null))));

        assertThat(missing.temporary())
                .overridingErrorMessage("Вложение, которого нет в хранилище, не появится при повторе")
                .isFalse();
        assertThat(unavailable.temporary())
                .overridingErrorMessage("Недоступное хранилище — временная причина")
                .isTrue();
    }

    @Test
    @DisplayName("Письмо, которое не собирается, — постоянный отказ без обращения к серверу")
    void assemblyFailureIsPermanent() {
        EmailTransportException thrown =
                catchThrowableOfType(EmailTransportException.class, () -> transport.send(unassemblable(), RECIPIENTS));

        assertThat(thrown).isNotNull();
        assertThat(thrown.getFailure().temporary())
                .overridingErrorMessage("Письмо, которое не собралось, не соберётся и при повторе")
                .isFalse();
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("Проверка при приёме: собираемое письмо проходит, несобираемое — ошибка валидации")
    void verifyEmail() {
        assertThatCode(() -> transport.verify(TestData.acceptedEmail())).doesNotThrowAnyException();
        assertThatThrownBy(() -> transport.verify(unassemblable()))
                .overridingErrorMessage("Клиент должен получить отказ при приёме, а не FAILED после")
                .isInstanceOf(ServiceValidationException.class);
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    private DeliveryFailure failureOf(RuntimeException sendError) {
        doThrow(sendError).when(mailSender).send(any(MimeMessage.class));

        EmailTransportException thrown =
                catchThrowableOfType(EmailTransportException.class, () -> transport.send(TestData.acceptedEmail(), RECIPIENTS));

        assertThat(thrown)
                .overridingErrorMessage("Сбой передачи должен превращаться в EmailTransportException")
                .isNotNull();
        return thrown.getFailure();
    }

    private static Email unassemblable() {
        EmailMessage message = new EmailMessage(
                mailbox("noreply@example.org"), null,
                new Recipients(List.of(mailbox("user@")), null, null),
                "Тема", List.of(BodyAlternative.plainText("текст")), null, null);
        return new Email(EmailState.accepted(EmailId.newId(), NOW), message, List.of());
    }
}
