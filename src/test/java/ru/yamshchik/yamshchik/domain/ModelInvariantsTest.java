package ru.yamshchik.yamshchik.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import ru.yamshchik.yamshchik.TestData;
import ru.yamshchik.yamshchik.config.exception.type.ServiceValidationException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ru.yamshchik.yamshchik.TestData.NOW;
import static ru.yamshchik.yamshchik.TestData.SHA256;
import static ru.yamshchik.yamshchik.TestData.mailbox;


@DisplayName("Инварианты доменной модели")
class ModelInvariantsTest {

    private static final AttachmentDisposition REGULAR = AttachmentDisposition.ATTACHMENT;

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "user", "@example.org", "user@example.org\r\nBcc: x@y.z"})
    @DisplayName("Некорректный почтовый адрес отклоняется")
    void invalidMailbox(String address) {
        assertThatThrownBy(() -> new Mailbox(address, null))
                .overridingErrorMessage("Адрес «%s» не должен приниматься", address)
                .isInstanceOf(ServiceValidationException.class);
    }

    @Test
    @DisplayName("Перевод строки в имени адресата запрещён")
    void mailboxNameMustBeSingleLine() {
        assertThatThrownBy(() -> new Mailbox("user@example.org", "Иван\nBcc: x@y.z"))
                .isInstanceOf(ServiceValidationException.class);
    }

    @Test
    @DisplayName("Нужен хотя бы один получатель в любом из списков")
    void recipientsRequired() {
        assertThatThrownBy(() -> new Recipients(null, List.of(), null))
                .overridingErrorMessage("Письмо совсем без получателей должно отклоняться")
                .isInstanceOf(ServiceValidationException.class);
        assertThatCode(() -> new Recipients(null, null, List.of(mailbox("hidden@example.org"))))
                .overridingErrorMessage("Письмо только со скрытой копией допустимо")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Отсутствующие списки получателей становятся пустыми")
    void recipientsDefaults() {
        Recipients recipients = new Recipients(List.of(mailbox("user@example.org")), null, null);

        assertThat(recipients.cc()).isEmpty();
        assertThat(recipients.bcc()).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"image/png", "text/", "text/html\r\nX: y"})
    @DisplayName("Тело письма может быть только текстового типа")
    void bodyMediaType(String mediaType) {
        assertThatThrownBy(() -> new BodyAlternative(mediaType, "x"))
                .overridingErrorMessage("Тип тела «%s» не должен приниматься", mediaType)
                .isInstanceOf(ServiceValidationException.class);
    }

    @Test
    @DisplayName("Готовые варианты тела имеют верные типы")
    void bodyFactories() {
        assertThat(BodyAlternative.plainText("a").mediaType()).isEqualTo("text/plain");
        assertThat(BodyAlternative.html("<p>a</p>").mediaType()).isEqualTo("text/html");
        assertThatThrownBy(() -> BodyAlternative.plainText(null)).isInstanceOf(ServiceValidationException.class);
    }

    @Test
    @DisplayName("Встроенному вложению обязателен contentId")
    void inlineAttachmentRequiresContentId() {
        assertThatThrownBy(() -> new Attachment("logo.png", "image/png", AttachmentDisposition.INLINE, " ",
                                                "key", 1, SHA256))
                .overridingErrorMessage("На встроенное вложение без contentId нельзя сослаться из HTML")
                .isInstanceOf(ServiceValidationException.class);
        assertThatCode(() -> new Attachment("a.txt", "text/plain", REGULAR, null, "key", 1, SHA256))
                .overridingErrorMessage("Обычному вложению contentId не нужен")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Вложение без имени, типа или ссылки на содержимое отклоняется")
    void attachmentRequiredFields() {
        assertThatThrownBy(() -> new Attachment(" ", "text/plain", REGULAR, null, "key", 1, SHA256))
                .isInstanceOf(ServiceValidationException.class);
        assertThatThrownBy(() -> new Attachment("a.txt", null, REGULAR, null, "key", 1, SHA256))
                .isInstanceOf(ServiceValidationException.class);
        assertThatThrownBy(() -> new Attachment("a.txt", "text/plain", null, null, "key", 1, SHA256))
                .isInstanceOf(ServiceValidationException.class);
        assertThatThrownBy(() -> new Attachment("a.txt", "text/plain", REGULAR, null, " ", 1, SHA256))
                .overridingErrorMessage("Вложение без ключа хранения нельзя будет прочитать при отправке")
                .isInstanceOf(ServiceValidationException.class);
        assertThatThrownBy(() -> new Attachment("a.txt", "text/plain", REGULAR, null, "key", -1, SHA256))
                .isInstanceOf(ServiceValidationException.class);
        assertThatThrownBy(() -> new Attachment("a.txt", "text/plain", REGULAR, null, "key", 1, null))
                .isInstanceOf(ServiceValidationException.class);
        assertThatThrownBy(() -> new Attachment("a\r\n.txt", "text/plain", REGULAR, null, "key", 1, SHA256))
                .overridingErrorMessage("Перевод строки в имени файла попал бы в заголовки письма")
                .isInstanceOf(ServiceValidationException.class);
    }

    @Test
    @DisplayName("Смена ключа хранения не меняет остальные свойства вложения")
    void withStorageKey() {
        Attachment original = TestData.inlineAttachment("logo.png", "logo");

        Attachment moved = original.withStorageKey("0/42");

        assertThat(moved.storageKey()).isEqualTo("0/42");
        assertThat(moved)
                .overridingErrorMessage("Кроме ключа хранения во вложении ничего не должно измениться")
                .usingRecursiveComparison().ignoringFields("storageKey").isEqualTo(original);
    }

    @Test
    @DisplayName("Письмо без тела и без вложений отклоняется")
    void emailNeedsBodyOrAttachment() {
        EmailState state = EmailState.accepted(EmailId.newId(), NOW);
        EmailMessage empty = TestData.message(List.of());

        assertThatThrownBy(() -> new Email(state, empty, List.of()))
                .overridingErrorMessage("Пустое письмо не должно приниматься")
                .isInstanceOf(ServiceValidationException.class);
        assertThatCode(() -> new Email(state, empty, List.of(TestData.attachment("a.txt"))))
                .overridingErrorMessage("Письмо из одних вложений допустимо")
                .doesNotThrowAnyException();
    }
}
