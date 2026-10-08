package ru.yamshchik.yamshchik.adapter.in.rest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import ru.yamshchik.yamshchik.adapter.in.rest.dto.AttachmentDispositionDto;
import ru.yamshchik.yamshchik.adapter.in.rest.dto.AttachmentOptionsDto;
import ru.yamshchik.yamshchik.adapter.in.rest.dto.BodyDto;
import ru.yamshchik.yamshchik.adapter.in.rest.dto.EmailRequestDto;
import ru.yamshchik.yamshchik.adapter.in.rest.dto.EmailStateDto;
import ru.yamshchik.yamshchik.adapter.in.rest.dto.EmailStatusDto;
import ru.yamshchik.yamshchik.adapter.in.rest.dto.MailboxDto;
import ru.yamshchik.yamshchik.adapter.in.rest.dto.PriorityDto;
import ru.yamshchik.yamshchik.application.port.in.SubmitEmailUseCase.AttachmentUpload;
import ru.yamshchik.yamshchik.application.port.in.SubmitEmailUseCase.SubmitEmailCommand;
import ru.yamshchik.yamshchik.config.exception.type.ServiceValidationException;
import ru.yamshchik.yamshchik.domain.AttachmentDisposition;
import ru.yamshchik.yamshchik.domain.BodyAlternative;
import ru.yamshchik.yamshchik.domain.EmailId;
import ru.yamshchik.yamshchik.domain.EmailMessage;
import ru.yamshchik.yamshchik.domain.EmailState;
import ru.yamshchik.yamshchik.domain.Priority;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ru.yamshchik.yamshchik.TestData.LEASE;
import static ru.yamshchik.yamshchik.TestData.NOW;


@DisplayName("Преобразование запросов и ответов API")
class EmailRestMapperTest {

    private static final byte[] CONTENT = "abc".getBytes(StandardCharsets.UTF_8);

    private final EmailRestMapper mapper = new EmailRestMapperImpl();

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

        assertThat(message.from().address()).isEqualTo("noreply@example.org");
        assertThat(message.from().name())
                .overridingErrorMessage("Отображаемое имя отправителя потерялось при преобразовании")
                .isEqualTo("Ямщик");
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

        assertThat(mapper.toCommand(request, files("a.txt")).message().body()).isEmpty();
    }

    @Test
    @DisplayName("Файл без параметров — обычное вложение")
    void fileWithoutOptions() throws Exception {
        SubmitEmailCommand command = mapper.toCommand(request(), files("a.txt"));

        AttachmentUpload upload = command.attachments().getFirst();
        assertThat(upload.filename()).isEqualTo("a.txt");
        assertThat(upload.mediaType()).isEqualTo("text/plain");
        assertThat(upload.disposition())
                .overridingErrorMessage("Файл без параметров должен отправляться обычным вложением")
                .isEqualTo(AttachmentDisposition.ATTACHMENT);
        assertThat(upload.contentId()).isNull();
        assertThat(upload.size()).isEqualTo(CONTENT.length);
        try (InputStream content = upload.content().open()) {
            assertThat(content.readAllBytes()).isEqualTo(CONTENT);
        }
    }

    @Test
    @DisplayName("Параметры вложения сопоставляются с файлом по имени")
    void optionsMatchedByFilename() {
        EmailRequestDto request = request();
        AttachmentOptionsDto options = new AttachmentOptionsDto();
        options.setFilename("logo.png");
        options.setDisposition(AttachmentDispositionDto.INLINE);
        options.setContentId("logo");
        request.setAttachmentOptions(List.of(options));

        List<AttachmentUpload> uploads = mapper.toCommand(request, files("a.txt", "logo.png")).attachments();

        assertThat(uploads.get(0).disposition()).isEqualTo(AttachmentDisposition.ATTACHMENT);
        assertThat(uploads.get(1).disposition())
                .overridingErrorMessage("Параметры должны применяться к файлу logo.png, а не к первому по порядку")
                .isEqualTo(AttachmentDisposition.INLINE);
        assertThat(uploads.get(1).contentId()).isEqualTo("logo");
    }

    @Test
    @DisplayName("Файл без типа получает application/octet-stream")
    void defaultMediaType() {
        MultipartFile untyped = new MockMultipartFile("attachments", "data.bin", null, CONTENT);

        AttachmentUpload upload = mapper.toCommand(request(), List.of(untyped)).attachments().getFirst();

        assertThat(upload.mediaType()).isEqualTo("application/octet-stream");
    }

    @Test
    @DisplayName("Параметры для незагруженного файла — ошибка")
    void optionsWithoutFile() {
        EmailRequestDto request = request();
        AttachmentOptionsDto options = new AttachmentOptionsDto();
        options.setFilename("missing.png");
        request.setAttachmentOptions(List.of(options));

        assertThatThrownBy(() -> mapper.toCommand(request, files("a.txt")))
                .overridingErrorMessage("Параметры вложения, для которого нет файла, должны отклоняться")
                .isInstanceOf(ServiceValidationException.class)
                .hasMessageContaining("missing.png");
    }

    @Test
    @DisplayName("Состояние письма преобразуется в ответ API")
    void stateToDto() {
        EmailState sent = EmailState.accepted(EmailId.newId(), NOW).startSending(NOW, LEASE).sent(NOW.plusSeconds(1));

        EmailStateDto dto = mapper.toDto(sent);

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

        assertThat(mapper.toDto(deferred).getNextAttemptAt()).isEqualTo(NOW.plusSeconds(30).atOffset(ZoneOffset.UTC));
        assertThat(mapper.toDto(deferred).getLastError()).isEqualTo("451");
        assertThat(mapper.toDto(sending).getNextAttemptAt())
                .overridingErrorMessage("Срок аренды письма в отправке не должен выдаваться за время следующей попытки")
                .isNull();
    }

    private static EmailRequestDto request() {
        EmailRequestDto request = new EmailRequestDto();
        request.setFrom(mailbox("noreply@example.org", "Ямщик"));
        request.setTo(List.of(mailbox("user@example.org", null)));
        request.setSubject("Тема");
        BodyDto body = new BodyDto();
        body.setText("текст");
        request.setBody(body);
        return request;
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
