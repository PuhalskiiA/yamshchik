package ru.yamshchik.yamshchik.adapter.in.rest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import ru.yamshchik.yamshchik.TestData;
import ru.yamshchik.yamshchik.adapter.in.rest.dto.AttachmentDispositionDto;
import ru.yamshchik.yamshchik.adapter.in.rest.dto.AttachmentOptionsDto;
import ru.yamshchik.yamshchik.adapter.in.rest.dto.BodyDto;
import ru.yamshchik.yamshchik.adapter.in.rest.dto.DeliveryDto;
import ru.yamshchik.yamshchik.adapter.in.rest.dto.DeliveryStatusDto;
import ru.yamshchik.yamshchik.adapter.in.rest.dto.EmailRequestDto;
import ru.yamshchik.yamshchik.adapter.in.rest.dto.EmailStateDto;
import ru.yamshchik.yamshchik.adapter.in.rest.dto.EmailStatusDto;
import ru.yamshchik.yamshchik.adapter.in.rest.dto.EmailSubmissionDto;
import ru.yamshchik.yamshchik.adapter.in.rest.dto.MailboxDto;
import ru.yamshchik.yamshchik.adapter.in.rest.dto.PriorityDto;
import ru.yamshchik.yamshchik.adapter.in.rest.dto.ReportActionDto;
import ru.yamshchik.yamshchik.application.port.in.EmailView;
import ru.yamshchik.yamshchik.application.port.in.SubmitEmailUseCase.AttachmentUpload;
import ru.yamshchik.yamshchik.application.port.in.SubmitEmailUseCase.SubmitEmailCommand;
import ru.yamshchik.yamshchik.config.exception.type.ServiceValidationException;
import ru.yamshchik.yamshchik.config.properties.SenderProperties;
import ru.yamshchik.yamshchik.config.properties.YamshchikProperties;
import ru.yamshchik.yamshchik.domain.AttachmentDisposition;
import ru.yamshchik.yamshchik.domain.BodyAlternative;
import ru.yamshchik.yamshchik.domain.Delivery;
import ru.yamshchik.yamshchik.domain.DeliveryReport;
import ru.yamshchik.yamshchik.domain.EmailId;
import ru.yamshchik.yamshchik.domain.EmailMessage;
import ru.yamshchik.yamshchik.domain.EmailState;
import ru.yamshchik.yamshchik.domain.Mailbox;
import ru.yamshchik.yamshchik.domain.Priority;
import ru.yamshchik.yamshchik.domain.ReportAction;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static ru.yamshchik.yamshchik.TestData.LEASE;
import static ru.yamshchik.yamshchik.TestData.NOW;


@DisplayName("Преобразование запросов и ответов API")
class EmailRestMapperTest {

    private static final byte[] CONTENT = "abc".getBytes(StandardCharsets.UTF_8);

    private final EmailRestMapper mapper = new EmailRestMapperImpl();

    @BeforeEach
    void setUp() {
        SenderProperties sender = new SenderProperties();
        sender.setAddress("noreply@example.org");
        sender.setName("Ямщик");
        YamshchikProperties properties = new YamshchikProperties();
        properties.setSender(sender);
        mapper.properties = properties;
    }

    @Test
    @DisplayName("Все поля запроса попадают в письмо")
    void requestToMessage() {
        EmailRequestDto request = request();
        request.setReplyTo(mailbox("reply@example.org", null));
        request.setCc(List.of(mailbox("cc@example.org", null)));
        request.setBcc(List.of(mailbox("bcc@example.org", null)));
        request.setPriority(PriorityDto.HIGH);
        request.setHeaders(Map.of("X-Request-Id", "42"));

        EmailMessage message = mapper.toCommand(request, null).message();

        assertThat(message.from())
                .overridingErrorMessage("Отправитель должен браться из настроек сервиса: клиент его не задаёт")
                .isEqualTo(new Mailbox("noreply@example.org", "Ямщик"));
        assertThat(message.replyTo().address()).isEqualTo("reply@example.org");
        assertThat(message.recipients().to()).extracting("address").containsExactly("user@example.org");
        assertThat(message.recipients().cc()).extracting("address").containsExactly("cc@example.org");
        assertThat(message.recipients().bcc())
                .overridingErrorMessage("Скрытая копия не должна теряться или попадать в другой список")
                .extracting("address").containsExactly("bcc@example.org");
        assertThat(message.subject()).isEqualTo("Тема");
        assertThat(message.priority()).isEqualTo(Priority.HIGH);
        assertThat(message.headers()).containsEntry("X-Request-Id", "42");
    }

    @Test
    @DisplayName("Признак персональной отправки переносится в команду, по умолчанию он выключен")
    void personalFlag() {
        EmailRequestDto personal = request();
        personal.setPersonal(true);
        EmailRequestDto unset = request();
        unset.setPersonal(null);

        assertThat(mapper.toCommand(personal, null).personal()).isTrue();
        assertThat(mapper.toCommand(unset, null).personal())
                .overridingErrorMessage("Запрос без признака — обычная отправка, а не персональная")
                .isFalse();
    }

    @Test
    @DisplayName("Ответ на приём содержит состояния всех принятых писем по порядку")
    void statesToSubmissionDto() {
        EmailState first = EmailState.accepted(EmailId.newId(), NOW);
        EmailState second = EmailState.accepted(EmailId.newId(), NOW);

        EmailSubmissionDto dto = mapper.toSubmissionDto(List.of(view(first), view(second)));

        assertThat(dto.getEmails()).extracting(EmailStateDto::getId)
                .overridingErrorMessage("Порядок писем в ответе должен совпадать с порядком получателей в запросе")
                .containsExactly(first.id().value(), second.id().value());
    }

    @Test
    @DisplayName("Варианты тела идут в порядке: текст, затем HTML")
    void bodyOrder() {
        EmailRequestDto request = request();
        BodyDto body = new BodyDto();
        body.setHtml("<p>html</p>");
        body.setText("текст");
        request.setBody(body);

        List<BodyAlternative> alternatives = mapper.toCommand(request, null).message().body();

        assertThat(alternatives).extracting(BodyAlternative::mediaType)
                .overridingErrorMessage("HTML должен идти после текста независимо от порядка полей в запросе")
                .containsExactly("text/plain", "text/html");
    }

    @Test
    @DisplayName("Запрос без тела даёт письмо с пустым телом")
    void noBody() {
        EmailRequestDto request = request();
        request.setBody(null);

        request.setAttachmentOptions(List.of(options("a.txt")));

        assertThat(mapper.toCommand(request, files("a.txt")).message().body()).isEmpty();
    }

    @Test
    @DisplayName("Файл с описанием: имя, тип, размер, сумма и содержимое переходят во вложение")
    void describedFile() throws Exception {
        EmailRequestDto request = request();
        request.setAttachmentOptions(List.of(options("a.txt")));

        AttachmentUpload upload = mapper.toCommand(request, files("a.txt")).attachments().getFirst();

        assertThat(upload.filename()).isEqualTo("a.txt");
        assertThat(upload.mediaType()).isEqualTo("text/plain");
        assertThat(upload.disposition())
                .overridingErrorMessage("Без указанного способа вложение должно быть обычным")
                .isEqualTo(AttachmentDisposition.ATTACHMENT);
        assertThat(upload.contentId()).isNull();
        assertThat(upload.sha256()).isEqualTo(TestData.SHA256);
        assertThat(upload.size()).isEqualTo(CONTENT.length);
        try (InputStream content = upload.content().open()) {
            assertThat(content.readAllBytes()).isEqualTo(CONTENT);
        }
    }

    @Test
    @DisplayName("Вложения идут в порядке описаний, параметры сопоставляются с файлом по имени")
    void optionsMatchedByFilename() {
        EmailRequestDto request = request();
        AttachmentOptionsDto logo = options("logo.png");
        logo.setDisposition(AttachmentDispositionDto.INLINE);
        logo.setContentId("logo");
        request.setAttachmentOptions(List.of(logo, options("a.txt")));

        List<AttachmentUpload> uploads = mapper.toCommand(request, files("a.txt", "logo.png")).attachments();

        assertThat(uploads).extracting(AttachmentUpload::filename)
                .overridingErrorMessage("Порядок вложений задают описания, а не порядок файлов в запросе")
                .containsExactly("logo.png", "a.txt");
        assertThat(uploads.get(0).disposition()).isEqualTo(AttachmentDisposition.INLINE);
        assertThat(uploads.get(0).contentId()).isEqualTo("logo");
    }

    @Test
    @DisplayName("Файл без типа получает application/octet-stream")
    void defaultMediaType() {
        MultipartFile untyped = new MockMultipartFile("attachments", "data.bin", null, CONTENT);
        EmailRequestDto request = request();
        request.setAttachmentOptions(List.of(options("data.bin")));

        AttachmentUpload upload = mapper.toCommand(request, List.of(untyped)).attachments().getFirst();

        assertThat(upload.mediaType()).isEqualTo("application/octet-stream");
    }

    @Test
    @DisplayName("Описание без файла — ссылка на ранее принятое вложение по сумме")
    void optionsWithoutFile() {
        EmailRequestDto request = request();
        request.setAttachmentOptions(List.of(options("report.pdf")));

        AttachmentUpload upload = mapper.toCommand(request, null).attachments().getFirst();

        assertThat(upload.hasContent())
                .overridingErrorMessage("Файл не прислан — содержимого у вложения быть не должно, его возьмут из хранилища")
                .isFalse();
        assertThat(upload.sha256()).isEqualTo(TestData.SHA256);
        assertThat(upload.mediaType()).isNull();
    }

    @Test
    @DisplayName("Файл без описания в attachmentOptions — ошибка")
    void fileWithoutOptions() {
        assertThatThrownBy(() -> mapper.toCommand(request(), files("a.txt")))
                .overridingErrorMessage("У каждого файла должна быть контрольная сумма в описании")
                .isInstanceOf(ServiceValidationException.class)
                .hasMessageContaining("a.txt");
    }

    @Test
    @DisplayName("Один и тот же файл или его описание дважды — ошибка")
    void duplicates() {
        EmailRequestDto twiceDescribed = request();
        twiceDescribed.setAttachmentOptions(List.of(options("a.txt"), options("a.txt")));
        EmailRequestDto described = request();
        described.setAttachmentOptions(List.of(options("a.txt")));

        assertThatThrownBy(() -> mapper.toCommand(twiceDescribed, files("a.txt")))
                .isInstanceOf(ServiceValidationException.class);
        assertThatThrownBy(() -> mapper.toCommand(described, files("a.txt", "a.txt")))
                .overridingErrorMessage("По имени нельзя понять, к какому из двух файлов относится описание")
                .isInstanceOf(ServiceValidationException.class);
    }

    @Test
    @DisplayName("Состояние письма преобразуется в ответ API")
    void stateToDto() {
        EmailState sent = EmailState.accepted(EmailId.newId(), NOW).startSending(NOW, LEASE).sent(NOW.plusSeconds(1));

        EmailStateDto dto = mapper.toDto(view(sent));

        assertThat(dto.getId()).isEqualTo(sent.id().value());
        assertThat(dto.getStatus()).isEqualTo(EmailStatusDto.SENT);
        assertThat(dto.getAttempts()).isEqualTo(1);
        assertThat(dto.getCreatedAt()).isEqualTo(NOW.atOffset(ZoneOffset.UTC));
        assertThat(dto.getSentAt()).isEqualTo(NOW.plusSeconds(1).atOffset(ZoneOffset.UTC));
        assertThat(dto.getNextAttemptAt())
                .overridingErrorMessage("Время следующей попытки показывается только у отложенного письма")
                .isNull();
    }

    @Test
    @DisplayName("Время следующей попытки есть только у отложенного письма")
    void nextAttemptAt() {
        EmailState sending = EmailState.accepted(EmailId.newId(), NOW).startSending(NOW, LEASE);
        EmailState deferred = sending.deferred("451", NOW, NOW.plusSeconds(30));

        assertThat(mapper.toDto(view(deferred)).getNextAttemptAt()).isEqualTo(NOW.plusSeconds(30).atOffset(ZoneOffset.UTC));
        assertThat(mapper.toDto(view(deferred)).getLastError()).isEqualTo("451");
        assertThat(mapper.toDto(view(sending)).getNextAttemptAt())
                .overridingErrorMessage("Срок аренды письма в отправке не должен выдаваться за время следующей попытки")
                .isNull();
    }

    @Test
    @DisplayName("В ответ попадают пачка и доставка каждому получателю с последним отчётом")
    void deliveriesToDto() {
        EmailState sent = EmailState.accepted(EmailId.newId(), NOW).startSending(NOW, LEASE).sent(NOW);
        Delivery waiting = TestData.sentDelivery(sent.id(), "user@example.org", 1);
        DeliveryReport report = new DeliveryReport(sent.id(), 1, "copy@example.org", ReportAction.RELAYED, "2.0.0",
                                                   "smtp; 250 2.0.0 Ok");
        Delivery relayed = TestData.sentDelivery(sent.id(), "copy@example.org", 1).delivered(report, NOW);

        EmailStateDto dto = mapper.toDto(new EmailView(sent, List.of(waiting, relayed)));

        assertThat(dto.getBatchId())
                .overridingErrorMessage("Клиент должен видеть, из какой пачки письмо")
                .isEqualTo(TestData.BATCH_ID);
        assertThat(dto.getDeliveries()).extracting(DeliveryDto::getRecipient, DeliveryDto::getStatus,
                                                   DeliveryDto::getReportAction, DeliveryDto::getReportStatus)
                .overridingErrorMessage("Статус и последний отчёт должны показываться по каждому получателю")
                .containsExactly(tuple("user@example.org", DeliveryStatusDto.SENT, null, null),
                                 tuple("copy@example.org", DeliveryStatusDto.DELIVERED, ReportActionDto.RELAYED,
                                       "2.0.0"));
    }

    // Получатель у письма есть всегда, поэтому и в ответе без доставки не обходится
    private static EmailView view(EmailState state) {
        return new EmailView(state, List.of(TestData.pendingDelivery(state.id(), "user@example.org")));
    }

    private static EmailRequestDto request() {
        EmailRequestDto request = new EmailRequestDto();
        request.setTo(List.of(mailbox("user@example.org", null)));
        request.setSubject("Тема");
        BodyDto body = new BodyDto();
        body.setText("текст");
        request.setBody(body);
        return request;
    }

    private static AttachmentOptionsDto options(String filename) {
        AttachmentOptionsDto options = new AttachmentOptionsDto();
        options.setFilename(filename);
        options.setSha256(TestData.SHA256);
        return options;
    }

    private static MailboxDto mailbox(String address, String name) {
        MailboxDto mailbox = new MailboxDto();
        mailbox.setAddress(address);
        mailbox.setName(name);
        return mailbox;
    }

    private static List<MultipartFile> files(String... names) {
        return java.util.Arrays.stream(names)
                .<MultipartFile>map(name -> new MockMultipartFile("attachments", name, "text/plain", CONTENT))
                .toList();
    }
}
