package ru.yamshchik.yamshchik.application.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.util.unit.DataSize;
import ru.yamshchik.yamshchik.TestData;
import ru.yamshchik.yamshchik.application.port.in.SubmitEmailUseCase.AttachmentUpload;
import ru.yamshchik.yamshchik.application.port.in.SubmitEmailUseCase.SubmitEmailCommand;
import ru.yamshchik.yamshchik.application.port.out.AttachmentStorage;
import ru.yamshchik.yamshchik.application.port.out.DispatchQueue;
import ru.yamshchik.yamshchik.application.port.out.EmailMetrics;
import ru.yamshchik.yamshchik.application.port.out.EmailRepository;
import ru.yamshchik.yamshchik.application.port.out.EmailTransport;
import ru.yamshchik.yamshchik.config.exception.type.EmailTooLargeException;
import ru.yamshchik.yamshchik.config.exception.type.ServiceValidationException;
import ru.yamshchik.yamshchik.config.properties.IntakeProperties;
import ru.yamshchik.yamshchik.config.properties.YamshchikProperties;
import ru.yamshchik.yamshchik.domain.Attachment;
import ru.yamshchik.yamshchik.domain.AttachmentDisposition;
import ru.yamshchik.yamshchik.domain.BodyAlternative;
import ru.yamshchik.yamshchik.domain.Email;
import ru.yamshchik.yamshchik.domain.EmailMessage;
import ru.yamshchik.yamshchik.domain.EmailState;
import ru.yamshchik.yamshchik.domain.EmailStatus;
import ru.yamshchik.yamshchik.domain.Mailbox;
import ru.yamshchik.yamshchik.domain.Recipients;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static ru.yamshchik.yamshchik.TestData.NOW;
import static ru.yamshchik.yamshchik.TestData.SHA256;


@ExtendWith(MockitoExtension.class)
@DisplayName("Приём письма")
class EmailSubmissionServiceTest {

    private static final byte[] CONTENT = "abc".getBytes(StandardCharsets.UTF_8);

    @Mock
    private EmailRepository emailRepository;

    @Mock
    private AttachmentStorage attachmentStorage;

    @Mock
    private DispatchQueue dispatchQueue;

    @Mock
    private EmailTransport emailTransport;

    @Mock
    private EmailMetrics metrics;

    private IntakeProperties intake;

    private EmailSubmissionService service;

    @BeforeEach
    void setUp() {
        intake = new IntakeProperties();
        intake.setMaxMessageSize(DataSize.ofMegabytes(1));
        intake.setMaxRecipients(3);
        intake.setMaxAttachments(2);
        YamshchikProperties properties = new YamshchikProperties();
        properties.setIntake(intake);
        service = new EmailSubmissionService(emailRepository, attachmentStorage, dispatchQueue, emailTransport,
                                             properties, metrics, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("Принятое письмо сохраняется и ставится в очередь")
    void acceptedEmailIsStoredAndQueued() {
        EmailState state = service.submit(new SubmitEmailCommand(TestData.message(), List.of()));

        assertThat(state.status())
                .overridingErrorMessage("Ответ клиенту должен содержать статус ACCEPTED, а не %s", state.status())
                .isEqualTo(EmailStatus.ACCEPTED);
        assertThat(state.createdAt()).isEqualTo(NOW);
        Email saved = savedEmail();
        assertThat(saved.state()).isEqualTo(state);
        verify(dispatchQueue).enqueue(state.id(), NOW);
        verify(metrics).accepted();
    }

    @Test
    @DisplayName("Порядок шагов: проверка сборки, вложения, запись, очередь")
    void stepsOrder() {
        when(attachmentStorage.store(any(), any())).thenAnswer(call -> call.<Attachment>getArgument(0).storageKey());

        service.submit(new SubmitEmailCommand(TestData.message(), List.of(upload("a.txt"))));

        InOrder order = inOrder(emailTransport, attachmentStorage, emailRepository, dispatchQueue);
        order.verify(emailTransport).verify(any());
        order.verify(attachmentStorage).store(any(), any());
        order.verify(emailRepository).save(any());
        order.verify(dispatchQueue).enqueue(any(), any());
    }

    @Test
    @DisplayName("Вложение получает ключ, размер и контрольную сумму")
    void attachmentMetadata() {
        when(attachmentStorage.store(any(), any())).thenAnswer(call -> call.<Attachment>getArgument(0).storageKey());

        EmailState state = service.submit(new SubmitEmailCommand(TestData.message(), List.of(upload("a.txt"))));

        Attachment attachment = savedEmail().attachments().getFirst();
        assertThat(attachment.storageKey())
                .overridingErrorMessage("Ключ хранения должен строиться из идентификатора письма и номера вложения")
                .isEqualTo("emails/" + state.id() + "/0");
        assertThat(attachment.size()).isEqualTo(CONTENT.length);
        assertThat(attachment.sha256())
                .overridingErrorMessage("Контрольная сумма должна считаться по содержимому вложения")
                .isEqualTo(SHA256);
    }

    @Test
    @DisplayName("В письмо записывается ключ, который вернуло хранилище")
    void storageAssignedKeyIsSaved() {
        when(attachmentStorage.store(any(), any())).thenReturn("0/42");

        service.submit(new SubmitEmailCommand(TestData.message(), List.of(upload("a.txt"))));

        assertThat(savedEmail().attachments().getFirst().storageKey())
                .overridingErrorMessage("Хранилище может само назначить место: в БД должен попасть его ключ")
                .isEqualTo("0/42");
    }

    @Test
    @DisplayName("Письмо, которое не собирается, отклоняется до загрузки вложений")
    void unassemblableEmailIsRejectedBeforeUpload() {
        doThrow(new ServiceValidationException("Email cannot be assembled")).when(emailTransport).verify(any());

        assertThatThrownBy(() -> service.submit(new SubmitEmailCommand(TestData.message(), List.of(upload("a.txt")))))
                .isInstanceOf(ServiceValidationException.class);

        verifyNoInteractions(attachmentStorage, emailRepository, dispatchQueue, metrics);
    }

    @Test
    @DisplayName("Сбой записи в БД: загруженные вложения удаляются, письмо в очередь не попадает")
    void uploadedAttachmentsAreRemovedWhenSaveFails() {
        when(attachmentStorage.store(any(), any())).thenReturn("0/1", "0/2");
        doThrow(new IllegalStateException("db is down")).when(emailRepository).save(any());

        assertThatThrownBy(() -> service.submit(
                new SubmitEmailCommand(TestData.message(), List.of(upload("a.txt"), upload("b.txt")))))
                .isInstanceOf(IllegalStateException.class);

        ArgumentCaptor<Attachment> deleted = ArgumentCaptor.forClass(Attachment.class);
        verify(attachmentStorage, org.mockito.Mockito.times(2)).delete(deleted.capture());
        assertThat(deleted.getAllValues()).extracting(Attachment::storageKey)
                .overridingErrorMessage("Удаляться должны объекты под ключами, которые вернуло хранилище")
                .containsExactly("0/1", "0/2");
        verify(dispatchQueue, never()).enqueue(any(), any());
    }

    @Test
    @DisplayName("Сбой загрузки второго вложения: первое удаляется, запись в БД не делается")
    void partialUploadIsRolledBack() {
        when(attachmentStorage.store(any(), any())).thenReturn("0/1").thenThrow(new IllegalStateException("s3"));

        assertThatThrownBy(() -> service.submit(
                new SubmitEmailCommand(TestData.message(), List.of(upload("a.txt"), upload("b.txt")))))
                .isInstanceOf(IllegalStateException.class);

        verify(attachmentStorage).delete(any());
        verify(emailRepository, never()).save(any());
    }

    @Test
    @DisplayName("Слишком много получателей — отказ")
    void tooManyRecipients() {
        assertThatThrownBy(() -> service.submit(new SubmitEmailCommand(messageTo(4), List.of())))
                .overridingErrorMessage("При лимите в 3 получателя письмо на 4 должно отклоняться")
                .isInstanceOf(ServiceValidationException.class)
                .isNotInstanceOf(EmailTooLargeException.class);
        assertThatCode(() -> service.submit(new SubmitEmailCommand(messageTo(3), List.of())))
                .overridingErrorMessage("Письмо ровно на лимит получателей должно приниматься")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Слишком много вложений — отказ до чтения содержимого")
    void tooManyAttachments() {
        AtomicInteger opened = new AtomicInteger();
        List<AttachmentUpload> uploads = IntStream.range(0, 3)
                .mapToObj(i -> countingUpload("f" + i + ".txt", CONTENT.length, opened))
                .toList();

        assertThatThrownBy(() -> service.submit(new SubmitEmailCommand(TestData.message(), uploads)))
                .isInstanceOf(ServiceValidationException.class);

        assertThat(opened.get())
                .overridingErrorMessage("Лимиты должны проверяться до чтения файлов, а файлы открывались %d раз",
                                        opened.get())
                .isZero();
    }

    @Test
    @DisplayName("Письмо больше лимита после кодирования — отдельная ошибка размера")
    void tooLargeAfterEncoding() {
        AtomicInteger opened = new AtomicInteger();
        // 800 КБ файла после base64 дают больше 1 МБ
        AttachmentUpload large = countingUpload("big.bin", 800 * 1024, opened);

        assertThatThrownBy(() -> service.submit(new SubmitEmailCommand(TestData.message(), List.of(large))))
                .overridingErrorMessage("Файл на 800 КБ не помещается в письмо на 1 МБ после кодирования")
                .isInstanceOf(EmailTooLargeException.class);

        assertThat(opened.get()).isZero();
        verifyNoInteractions(emailTransport, attachmentStorage, emailRepository);
    }

    @Test
    @DisplayName("Тело письма учитывается в размере")
    void bodyCountsTowardsSize() {
        String hugeBody = "я".repeat(600 * 1024);
        EmailMessage message = TestData.message(List.of(BodyAlternative.plainText(hugeBody)));

        assertThatThrownBy(() -> service.submit(new SubmitEmailCommand(message, List.of())))
                .isInstanceOf(EmailTooLargeException.class);
    }

    @Test
    @DisplayName("Домен отправителя проверяется по списку без учёта регистра")
    void senderDomain() {
        intake.setAllowedSenderDomains(new ArrayList<>(List.of("Example.ORG")));

        assertThatCode(() -> service.submit(new SubmitEmailCommand(TestData.message(), List.of())))
                .overridingErrorMessage("Регистр домена в списке разрешённых не должен иметь значения")
                .doesNotThrowAnyException();

        intake.setAllowedSenderDomains(new ArrayList<>(List.of("other.org")));

        assertThatThrownBy(() -> service.submit(new SubmitEmailCommand(TestData.message(), List.of())))
                .overridingErrorMessage("Отправитель с домена вне списка должен отклоняться")
                .isInstanceOf(ServiceValidationException.class);
    }

    private Email savedEmail() {
        ArgumentCaptor<Email> captor = ArgumentCaptor.forClass(Email.class);
        verify(emailRepository).save(captor.capture());
        return captor.getValue();
    }

    private static AttachmentUpload upload(String filename) {
        return new AttachmentUpload(filename, "text/plain", AttachmentDisposition.ATTACHMENT, null,
                                    CONTENT.length, () -> new ByteArrayInputStream(CONTENT));
    }

    private static AttachmentUpload countingUpload(String filename, long size, AtomicInteger opened) {
        return new AttachmentUpload(filename, "text/plain", AttachmentDisposition.ATTACHMENT, null, size, () -> {
            opened.incrementAndGet();
            return new ByteArrayInputStream(CONTENT);
        });
    }

    private static EmailMessage messageTo(int recipients) {
        List<Mailbox> to = IntStream.range(0, recipients)
                .mapToObj(i -> TestData.mailbox("user" + i + "@example.org"))
                .toList();
        return new EmailMessage(TestData.mailbox("noreply@example.org"), null, new Recipients(to, null, null),
                                "Тема", List.of(BodyAlternative.plainText("текст")), null, null);
    }
}
