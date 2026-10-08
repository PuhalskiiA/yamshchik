package ru.yamshchik.yamshchik.adapter.out.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.yamshchik.yamshchik.TestData;
import ru.yamshchik.yamshchik.domain.Attachment;
import ru.yamshchik.yamshchik.domain.BodyAlternative;
import ru.yamshchik.yamshchik.domain.Email;
import ru.yamshchik.yamshchik.domain.EmailId;
import ru.yamshchik.yamshchik.domain.EmailMessage;
import ru.yamshchik.yamshchik.domain.EmailState;
import ru.yamshchik.yamshchik.domain.EmailStatus;
import ru.yamshchik.yamshchik.domain.Mailbox;
import ru.yamshchik.yamshchik.domain.Priority;
import ru.yamshchik.yamshchik.domain.Recipients;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static ru.yamshchik.yamshchik.TestData.LEASE;
import static ru.yamshchik.yamshchik.TestData.NOW;
import static ru.yamshchik.yamshchik.TestData.mailbox;


@DisplayName("Преобразование письма в записи БД и обратно")
class EmailEntityMapperTest {

    private EmailEntityMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new EmailEntityMapperImpl();
        mapper.jsonMapper = JsonMapper.builder().build();
    }

    @Test
    @DisplayName("Письмо без потерь проходит путь в БД и обратно")
    void roundTrip() {
        Email email = fullEmail();

        Email restored = mapper.toEmail(mapper.toEntity(email));

        assertThat(restored)
                .overridingErrorMessage("После сохранения и чтения письмо должно совпадать с исходным")
                .usingRecursiveComparison().isEqualTo(email);
    }

    @Test
    @DisplayName("Состояние письма раскладывается по колонкам")
    void stateColumns() {
        Email email = fullEmail();

        EmailEntity entity = mapper.toEntity(email);

        assertThat(entity.getId()).isEqualTo(email.id().value());
        assertThat(entity.getStatus()).isEqualTo(EmailStatus.ACCEPTED);
        assertThat(entity.getCreatedAt()).isEqualTo(NOW);
        assertThat(entity.getAvailableAt())
                .overridingErrorMessage("Без времени доступности письмо не попадёт в выборку очереди")
                .isEqualTo(NOW);
        assertThat(entity.isNew())
                .overridingErrorMessage("Новая запись должна вставляться, а не искаться в БД перед вставкой")
                .isTrue();
    }

    @Test
    @DisplayName("Вложения получают ссылку на письмо и порядковый номер")
    void attachmentsAreLinked() {
        EmailEntity entity = mapper.toEntity(fullEmail());

        List<EmailAttachmentEntity> attachments = entity.getAttachments();
        assertThat(attachments).hasSize(2);
        assertThat(attachments).extracting(EmailAttachmentEntity::getPosition)
                .overridingErrorMessage("Порядок вложений должен сохраняться номерами 0, 1, …")
                .containsExactly(0, 1);
        assertThat(attachments).allSatisfy(attachment ->
                assertThat(attachment.getEmail())
                        .overridingErrorMessage("Вложение без ссылки на письмо не сохранится из-за внешнего ключа")
                        .isSameAs(entity));
        assertThat(attachments.get(1).getContentId()).isEqualTo("logo");
        assertThat(attachments.get(0).getSizeBytes()).isEqualTo(3);
    }

    @Test
    @DisplayName("Обновление состояния не трогает содержимое, вложения и время приёма")
    void applyStateKeepsContent() {
        Email email = fullEmail();
        EmailEntity entity = mapper.toEntity(email);
        String messageJson = entity.getMessage();
        EmailState sending = email.state().startSending(NOW.plusSeconds(5), LEASE);

        mapper.applyState(entity, sending);

        assertThat(entity.getStatus()).isEqualTo(EmailStatus.SENDING);
        assertThat(entity.getAttempts()).isEqualTo(1);
        assertThat(entity.getUpdatedAt()).isEqualTo(NOW.plusSeconds(5));
        assertThat(entity.getAvailableAt()).isEqualTo(NOW.plusSeconds(5).plus(LEASE));
        assertThat(entity.getCreatedAt())
                .overridingErrorMessage("Время приёма письма не должно меняться при смене состояния")
                .isEqualTo(NOW);
        assertThat(entity.getMessage()).isEqualTo(messageJson);
        assertThat(entity.getAttachments())
                .overridingErrorMessage("Смена состояния не должна терять вложения")
                .hasSize(2);
    }

    @Test
    @DisplayName("Состояние читается из записи")
    void toState() {
        Email email = fullEmail();
        EmailState deferred = email.state().startSending(NOW, LEASE).deferred("451", NOW, NOW.plusSeconds(30));
        EmailEntity entity = mapper.toEntity(email);
        mapper.applyState(entity, deferred);

        assertThat(mapper.toState(entity))
                .overridingErrorMessage("Состояние из записи БД должно совпадать с записанным")
                .isEqualTo(deferred);
    }

    private static Email fullEmail() {
        EmailMessage message = new EmailMessage(
                new Mailbox("noreply@example.org", "Ямщик"),
                mailbox("reply@example.org"),
                new Recipients(List.of(mailbox("to@example.org")), List.of(mailbox("cc@example.org")),
                               List.of(mailbox("bcc@example.org"))),
                "Тема",
                List.of(BodyAlternative.plainText("текст"), BodyAlternative.html("<p>html</p>")),
                Priority.HIGH,
                Map.of("X-Request-Id", "42"));
        List<Attachment> attachments =
                List.of(TestData.attachment("a.txt"), TestData.inlineAttachment("logo.png", "logo"));
        return new Email(EmailState.accepted(EmailId.newId(), NOW), message, attachments);
    }
}
