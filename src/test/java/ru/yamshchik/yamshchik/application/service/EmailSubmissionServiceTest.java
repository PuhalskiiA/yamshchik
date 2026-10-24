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
import ru.yamshchik.yamshchik.application.port.in.EmailView;
import ru.yamshchik.yamshchik.application.port.in.SubmitEmailUseCase.AttachmentUpload;
import ru.yamshchik.yamshchik.application.port.in.SubmitEmailUseCase.SubmitEmailCommand;
import ru.yamshchik.yamshchik.application.port.out.AttachmentStorage;
import ru.yamshchik.yamshchik.application.port.out.DispatchQueue;
import ru.yamshchik.yamshchik.application.port.out.EmailMetrics;
import ru.yamshchik.yamshchik.application.port.out.EmailRepository;
import ru.yamshchik.yamshchik.application.port.out.EmailTransport;
import ru.yamshchik.yamshchik.application.port.out.StoredAttachments;
import ru.yamshchik.yamshchik.config.exception.type.EmailTooLargeException;
import ru.yamshchik.yamshchik.config.exception.type.MissingAttachmentsException;
import ru.yamshchik.yamshchik.config.exception.type.ServiceValidationException;
import ru.yamshchik.yamshchik.config.properties.IntakeProperties;
import ru.yamshchik.yamshchik.config.properties.YamshchikProperties;
import ru.yamshchik.yamshchik.domain.Attachment;
import ru.yamshchik.yamshchik.domain.AttachmentDisposition;
import ru.yamshchik.yamshchik.domain.BodyAlternative;
import ru.yamshchik.yamshchik.domain.Delivery;
import ru.yamshchik.yamshchik.domain.DeliveryStatus;
import ru.yamshchik.yamshchik.domain.Email;
import ru.yamshchik.yamshchik.domain.EmailMessage;
import ru.yamshchik.yamshchik.domain.EmailState;
import ru.yamshchik.yamshchik.domain.EmailStatus;
import ru.yamshchik.yamshchik.domain.Mailbox;
import ru.yamshchik.yamshchik.domain.Priority;
import ru.yamshchik.yamshchik.domain.Recipients;
import ru.yamshchik.yamshchik.domain.TrafficClass;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static ru.yamshchik.yamshchik.TestData.NOW;
import static ru.yamshchik.yamshchik.TestData.SHA256;


@ExtendWith(MockitoExtension.class)
@DisplayName("Приём письма")
class EmailSubmissionServiceTest {

    private static final byte[] CONTENT = "abc".getBytes(StandardCharsets.UTF_8);

    private static final byte[] OTHER_CONTENT = "xyz".getBytes(StandardCharsets.UTF_8);

    private static final Attachment STORED = new Attachment("old.png", "image/png", AttachmentDisposition.ATTACHMENT,
                                                            null, "emails/old/0", 3, SHA256);

    @Mock
    private EmailRepository emailRepository;

    @Mock
    private AttachmentStorage attachmentStorage;

    @Mock
    private StoredAttachments storedAttachments;

    @Mock
    private DispatchQueue dispatchQueue;

    @Mock
    private EmailTransport emailTransport;

    @Mock
    private EmailMetrics metrics;

    private EmailSubmissionService service;

    @BeforeEach
    void setUp() {
        IntakeProperties intake = new IntakeProperties();
        intake.setMaxMessageSize(DataSize.ofMegabytes(1));
        intake.setMaxRecipients(3);
        intake.setMaxAttachments(2);
        YamshchikProperties properties = new YamshchikProperties();
        properties.setIntake(intake);
        service = new EmailSubmissionService(emailRepository, attachmentStorage, storedAttachments, dispatchQueue,
                                             emailTransport, properties, metrics, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("Принятое письмо сохраняется и ставится в очередь")
    void acceptedEmailIsStoredAndQueued() {
        EmailState state = service.submit(command(TestData.message(), List.of())).getFirst().state();

        assertThat(state.status())
                .overridingErrorMessage("Ответ клиенту должен содержать статус ACCEPTED, а не %s", state.status())
                .isEqualTo(EmailStatus.ACCEPTED);
        assertThat(state.createdAt()).isEqualTo(NOW);
        Email saved = savedEmail();
        assertThat(saved.state()).isEqualTo(state);
        verify(dispatchQueue).enqueue(state.id(), TrafficClass.BULK, NOW);
        verify(metrics).accepted();
    }

    @Test
    @DisplayName("Порядок шагов: проверка сборки, вложения, запись, очередь")
    void stepsOrder() {
        when(attachmentStorage.store(any(), any())).thenAnswer(call -> call.<Attachment>getArgument(0).storageKey());

        service.submit(command(TestData.message(), List.of(upload("a.txt"))));

        InOrder order = inOrder(emailTransport, attachmentStorage, emailRepository, dispatchQueue);
        order.verify(emailTransport).verify(any());
        order.verify(attachmentStorage).store(any(), any());
        order.verify(emailRepository).saveAll(any(), any());
        order.verify(dispatchQueue).enqueue(any(), any(), any());
    }

    @Test
    @DisplayName("Вложение получает ключ, размер и контрольную сумму")
    void attachmentMetadata() {
        when(attachmentStorage.store(any(), any())).thenAnswer(call -> call.<Attachment>getArgument(0).storageKey());

        EmailState state = service.submit(command(TestData.message(), List.of(upload("a.txt")))).getFirst().state();

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

        service.submit(command(TestData.message(), List.of(upload("a.txt"))));

        assertThat(savedEmail().attachments().getFirst().storageKey())
                .overridingErrorMessage("Хранилище может само назначить место: в БД должен попасть его ключ")
                .isEqualTo("0/42");
    }

    @Test
    @DisplayName("Письмо, которое не собирается, отклоняется до загрузки вложений")
    void unassemblableEmailIsRejectedBeforeUpload() {
        doThrow(new ServiceValidationException("Email cannot be assembled")).when(emailTransport).verify(any());

        assertThatThrownBy(() -> service.submit(command(TestData.message(), List.of(upload("a.txt")))))
                .isInstanceOf(ServiceValidationException.class);

        verifyNoInteractions(attachmentStorage, emailRepository, dispatchQueue, metrics);
    }

    @Test
    @DisplayName("Сбой записи в БД: загруженные вложения удаляются, письмо в очередь не попадает")
    void uploadedAttachmentsAreRemovedWhenSaveFails() {
        when(attachmentStorage.store(any(), any())).thenReturn("0/1", "0/2");
        doThrow(new IllegalStateException("db is down")).when(emailRepository).saveAll(any(), any());

        assertThatThrownBy(() -> service.submit(
                command(TestData.message(), List.of(upload("a.txt"), upload("b.txt", OTHER_CONTENT)))))
                .isInstanceOf(IllegalStateException.class);

        ArgumentCaptor<Attachment> deleted = ArgumentCaptor.forClass(Attachment.class);
        verify(attachmentStorage, times(2)).delete(deleted.capture());
        assertThat(deleted.getAllValues()).extracting(Attachment::storageKey)
                .overridingErrorMessage("Удаляться должны объекты под ключами, которые вернуло хранилище")
                .containsExactly("0/1", "0/2");
        verify(dispatchQueue, never()).enqueue(any(), any(), any());
    }

    @Test
    @DisplayName("Сбой загрузки второго вложения: первое удаляется, запись в БД не делается")
    void partialUploadIsRolledBack() {
        when(attachmentStorage.store(any(), any())).thenReturn("0/1").thenThrow(new IllegalStateException("s3"));

        assertThatThrownBy(() -> service.submit(
                command(TestData.message(), List.of(upload("a.txt"), upload("b.txt", OTHER_CONTENT)))))
                .isInstanceOf(IllegalStateException.class);

        verify(attachmentStorage).delete(any());
        verify(emailRepository, never()).saveAll(any(), any());
    }

    @Test
    @DisplayName("Слишком много получателей — отказ")
    void tooManyRecipients() {
        assertThatThrownBy(() -> service.submit(command(messageTo(4), List.of())))
                .overridingErrorMessage("При лимите в 3 получателя письмо на 4 должно отклоняться")
                .isInstanceOf(ServiceValidationException.class)
                .isNotInstanceOf(EmailTooLargeException.class);
        assertThatCode(() -> service.submit(command(messageTo(3), List.of())))
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

        assertThatThrownBy(() -> service.submit(command(TestData.message(), uploads)))
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

        assertThatThrownBy(() -> service.submit(command(TestData.message(), List.of(large))))
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

        assertThatThrownBy(() -> service.submit(command(message, List.of())))
                .isInstanceOf(EmailTooLargeException.class);
    }

    @Test
    @DisplayName("Персональная отправка: каждому получателю — своё письмо")
    void personalSubmissionCreatesEmailPerRecipient() {
        List<EmailState> states = states(service.submit(personalCommand(messageTo(3), List.of())));

        assertThat(states)
                .overridingErrorMessage("На трёх получателей должно быть принято три письма, а принято %d", states.size())
                .hasSize(3);
        assertThat(states).extracting(EmailState::id)
                .overridingErrorMessage("У каждого письма должен быть свой идентификатор")
                .doesNotHaveDuplicates();
        List<Email> saved = savedEmails();
        assertThat(saved).extracting(Email::state)
                .overridingErrorMessage("Клиенту должны вернуться состояния именно сохранённых писем, в том же порядке")
                .containsExactlyElementsOf(states);
        assertThat(saved).extracting(email -> email.message().recipients().to())
                .overridingErrorMessage("В каждом письме должен быть один получатель, в порядке списка to")
                .containsExactly(List.of(TestData.mailbox("user0@example.org")),
                                 List.of(TestData.mailbox("user1@example.org")),
                                 List.of(TestData.mailbox("user2@example.org")));
        states.forEach(state -> verify(dispatchQueue).enqueue(state.id(), TrafficClass.BULK, NOW));
        verify(metrics, times(3)).accepted();
    }

    @Test
    @DisplayName("Персональная отправка: собираемость проверяется у каждого письма")
    void personalSubmissionVerifiesEveryEmail() {
        service.submit(personalCommand(messageTo(3), List.of()));

        verify(emailTransport, times(3)).verify(any());
    }

    @Test
    @DisplayName("Персональная отправка: вложение загружается один раз и общее для всех писем")
    void personalSubmissionStoresAttachmentOnce() {
        when(attachmentStorage.store(any(), any())).thenReturn("0/42");

        service.submit(personalCommand(messageTo(3), List.of(upload("a.txt"))));

        verify(attachmentStorage).store(any(), any());
        assertThat(savedEmails()).extracting(email -> email.attachments().getFirst().storageKey())
                .overridingErrorMessage("Все письма запроса должны ссылаться на один и тот же объект в хранилище")
                .containsExactly("0/42", "0/42", "0/42");
    }

    @Test
    @DisplayName("Персональная отправка с копией отклоняется до загрузки вложений")
    void personalSubmissionRejectsCopies() {
        EmailMessage withCopy = new EmailMessage(TestData.mailbox("noreply@example.org"), null,
                                                 new Recipients(List.of(TestData.mailbox("user@example.org")),
                                                                List.of(TestData.mailbox("cc@example.org")), null),
                                                 "Тема", List.of(BodyAlternative.plainText("текст")), null, null);

        assertThatThrownBy(() -> service.submit(personalCommand(withCopy, List.of(upload("a.txt")))))
                .overridingErrorMessage("Копия в персональной отправке должна отклоняться: неясно, кому её слать")
                .isInstanceOf(ServiceValidationException.class);

        verifyNoInteractions(emailTransport, attachmentStorage, emailRepository, dispatchQueue, metrics);
    }

    @Test
    @DisplayName("Обычная отправка нескольким получателям остаётся одним письмом")
    void ordinarySubmissionIsNotSplit() {
        List<EmailState> states = states(service.submit(command(messageTo(3), List.of())));

        assertThat(states)
                .overridingErrorMessage("Без признака персональной отправки письмо дробиться не должно")
                .hasSize(1);
        assertThat(savedEmail().message().recipients().to()).hasSize(3);
    }

    @Test
    @DisplayName("Доставка заводится на каждого получателя, включая копии; повторный адрес — одна доставка")
    void deliveryPerRecipient() {
        EmailMessage message = new EmailMessage(TestData.mailbox("noreply@example.org"), null,
                                                new Recipients(List.of(TestData.mailbox("User@Example.org")),
                                                               List.of(TestData.mailbox("copy@example.org")),
                                                               List.of(TestData.mailbox("user@example.org"))),
                                                "Тема", List.of(BodyAlternative.plainText("текст")), null, null);

        EmailView view = service.submit(command(message, List.of())).getFirst();

        assertThat(savedDeliveries()).extracting(Delivery::recipient)
                .overridingErrorMessage("Получатель, указанный дважды в разном регистре, должен получить письмо один раз")
                .containsExactly("user@example.org", "copy@example.org");
        assertThat(savedDeliveries())
                .overridingErrorMessage("До отправки каждая доставка ждёт её")
                .allSatisfy(delivery -> assertThat(delivery.status()).isEqualTo(DeliveryStatus.PENDING));
        assertThat(view.deliveries())
                .overridingErrorMessage("Клиенту должны вернуться те же доставки, что сохранены")
                .containsExactlyElementsOf(savedDeliveries());
    }

    @Test
    @DisplayName("Письма одного запроса объединены общей пачкой, у разных запросов пачки разные")
    void batchPerRequest() {
        service.submit(personalCommand(messageTo(3), List.of()));
        service.submit(command(TestData.message(), List.of()));

        ArgumentCaptor<List<Delivery>> captor = ArgumentCaptor.captor();
        verify(emailRepository, times(2)).saveAll(any(), captor.capture());
        List<Delivery> personal = captor.getAllValues().get(0);
        List<Delivery> single = captor.getAllValues().get(1);
        assertThat(personal).extracting(Delivery::batchId)
                .overridingErrorMessage("Все письма персональной отправки должны быть в одной пачке")
                .containsOnly(personal.getFirst().batchId());
        assertThat(personal).extracting(Delivery::emailId)
                .overridingErrorMessage("При персональной отправке у каждого получателя своё письмо")
                .doesNotHaveDuplicates();
        assertThat(single.getFirst().batchId())
                .overridingErrorMessage("Другой запрос — другая пачка")
                .isNotEqualTo(personal.getFirst().batchId());
    }

    @Test
    @DisplayName("Письмо с приоритетом HIGH встаёт в очередь срочных")
    void urgentEmailIsQueuedAsUrgent() {
        EmailMessage urgent = new EmailMessage(TestData.mailbox("noreply@example.org"), null,
                                               new Recipients(List.of(TestData.mailbox("user@example.org")), null, null),
                                               "Тема", List.of(BodyAlternative.plainText("текст")), Priority.HIGH, null);

        EmailState state = service.submit(command(urgent, List.of())).getFirst().state();

        verify(dispatchQueue).enqueue(state.id(), TrafficClass.URGENT, NOW);
    }

    @Test
    @DisplayName("Файл не прислан, вложение с такой суммой есть: письмо ссылается на него, повторной загрузки нет")
    void storedAttachmentIsReused() {
        when(storedAttachments.findBySha256(SHA256)).thenReturn(Optional.of(STORED));

        service.submit(command(TestData.message(), List.of(reference("report.png", SHA256))));

        verify(attachmentStorage, never()).store(any(), any());
        Attachment attachment = savedEmail().attachments().getFirst();
        assertThat(attachment.storageKey())
                .overridingErrorMessage("Письмо должно ссылаться на уже лежащий в хранилище объект")
                .isEqualTo("emails/old/0");
        assertThat(attachment.filename())
                .overridingErrorMessage("Имя файла у каждого письма своё, даже если содержимое общее")
                .isEqualTo("report.png");
        assertThat(attachment.mediaType()).isEqualTo("image/png");
        assertThat(attachment.size()).isEqualTo(3);
    }

    @Test
    @DisplayName("Файл прислан, но такое вложение уже есть: он сверяется с суммой и второй раз не загружается")
    void sentFileIsNotUploadedAgain() {
        when(storedAttachments.findBySha256(SHA256)).thenReturn(Optional.of(STORED));

        service.submit(command(TestData.message(), List.of(upload("a.txt"))));

        verify(attachmentStorage, never()).store(any(), any());
        assertThat(savedEmail().attachments().getFirst().storageKey()).isEqualTo("emails/old/0");
    }

    @Test
    @DisplayName("Файл не прислан и такого вложения нет: отказ со списком недостающих сумм")
    void missingAttachments() {
        String unknown = sha256(OTHER_CONTENT);

        assertThatThrownBy(() -> service.submit(command(TestData.message(), List.of(reference("x.pdf", unknown)))))
                .isInstanceOfSatisfying(MissingAttachmentsException.class, e -> assertThat(e.getMissing())
                        .overridingErrorMessage("Клиент должен узнать, какие именно файлы приложить")
                        .containsExactly(unknown));

        verifyNoInteractions(attachmentStorage, emailRepository, dispatchQueue);
    }

    @Test
    @DisplayName("Присланный файл не совпадает с заявленной суммой — отказ")
    void checksumMismatch() {
        AttachmentUpload wrong = new AttachmentUpload("a.txt", "text/plain", AttachmentDisposition.ATTACHMENT, null,
                                                      sha256(OTHER_CONTENT), CONTENT.length,
                                                      () -> new ByteArrayInputStream(CONTENT));

        assertThatThrownBy(() -> service.submit(command(TestData.message(), List.of(wrong))))
                .overridingErrorMessage("По неверной сумме вложение потом нашли бы другие запросы и получили чужой файл")
                .isInstanceOf(ServiceValidationException.class);

        verifyNoInteractions(attachmentStorage, emailRepository);
    }

    @Test
    @DisplayName("Один файл, приложенный к запросу дважды, загружается один раз")
    void sameFileTwiceIsUploadedOnce() {
        when(attachmentStorage.store(any(), any())).thenReturn("0/1");

        service.submit(command(TestData.message(), List.of(upload("a.txt"), upload("copy.txt"))));

        verify(attachmentStorage).store(any(), any());
        assertThat(savedEmail().attachments()).extracting(Attachment::storageKey).containsExactly("0/1", "0/1");
    }

    @Test
    @DisplayName("Сбой записи в БД: удаляется только загруженное сейчас, ранее принятое вложение остаётся")
    void reusedAttachmentIsNotDeletedOnFailure() {
        when(storedAttachments.findBySha256(SHA256)).thenReturn(Optional.of(STORED));
        when(attachmentStorage.store(any(), any())).thenReturn("0/2");
        doThrow(new IllegalStateException("db is down")).when(emailRepository).saveAll(any(), any());

        assertThatThrownBy(() -> service.submit(
                command(TestData.message(), List.of(reference("old.png", SHA256), upload("b.txt", OTHER_CONTENT)))))
                .isInstanceOf(IllegalStateException.class);

        ArgumentCaptor<Attachment> deleted = ArgumentCaptor.forClass(Attachment.class);
        verify(attachmentStorage).delete(deleted.capture());
        assertThat(deleted.getValue().storageKey())
                .overridingErrorMessage("Ранее принятое вложение принадлежит и другим письмам — удалять его нельзя")
                .isEqualTo("0/2");
    }

    private List<Email> savedEmails() {
        ArgumentCaptor<List<Email>> captor = ArgumentCaptor.captor();
        verify(emailRepository).saveAll(captor.capture(), any());
        return captor.getValue();
    }

    private List<Delivery> savedDeliveries() {
        ArgumentCaptor<List<Delivery>> captor = ArgumentCaptor.captor();
        verify(emailRepository).saveAll(any(), captor.capture());
        return captor.getValue();
    }

    private static List<EmailState> states(List<EmailView> views) {
        return views.stream().map(EmailView::state).toList();
    }

    private Email savedEmail() {
        return savedEmails().getFirst();
    }

    private static SubmitEmailCommand command(EmailMessage message, List<AttachmentUpload> uploads) {
        return new SubmitEmailCommand(message, uploads, false);
    }

    private static SubmitEmailCommand personalCommand(EmailMessage message, List<AttachmentUpload> uploads) {
        return new SubmitEmailCommand(message, uploads, true);
    }

    private static AttachmentUpload upload(String filename) {
        return upload(filename, CONTENT);
    }

    private static AttachmentUpload upload(String filename, byte[] content) {
        return new AttachmentUpload(filename, "text/plain", AttachmentDisposition.ATTACHMENT, null, sha256(content),
                                    content.length, () -> new ByteArrayInputStream(content));
    }

    // Файл не прислан — только контрольная сумма
    private static AttachmentUpload reference(String filename, String sha256) {
        return new AttachmentUpload(filename, null, AttachmentDisposition.ATTACHMENT, null, sha256, 0, null);
    }

    private static AttachmentUpload countingUpload(String filename, long size, AtomicInteger opened) {
        return new AttachmentUpload(filename, "text/plain", AttachmentDisposition.ATTACHMENT, null, SHA256, size, () -> {
            opened.incrementAndGet();
            return new ByteArrayInputStream(CONTENT);
        });
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static EmailMessage messageTo(int recipients) {
        List<Mailbox> to = IntStream.range(0, recipients)
                .mapToObj(i -> TestData.mailbox("user" + i + "@example.org"))
                .toList();
        return new EmailMessage(TestData.mailbox("noreply@example.org"), null, new Recipients(to, null, null),
                                "Тема", List.of(BodyAlternative.plainText("текст")), null, null);
    }
}
