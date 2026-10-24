package ru.yamshchik.yamshchik.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.yamshchik.yamshchik.config.exception.type.ServiceValidationException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ru.yamshchik.yamshchik.TestData.mailbox;


@DisplayName("Содержимое письма")
class EmailMessageTest {

    private static final Mailbox FROM = mailbox("noreply@example.org");

    private static final Recipients RECIPIENTS = new Recipients(List.of(mailbox("user@example.org")), null, null);

    @Test
    @DisplayName("Необязательные поля получают значения по умолчанию")
    void defaults() {
        EmailMessage message = new EmailMessage(FROM, null, RECIPIENTS, "Тема", null, null, null);

        assertThat(message.priority())
                .overridingErrorMessage("Без явного приоритета письмо должно быть обычным")
                .isEqualTo(Priority.NORMAL);
        assertThat(message.body())
                .overridingErrorMessage("Отсутствующее тело должно стать пустым списком, а не null")
                .isEmpty();
        assertThat(message.headers()).isEmpty();
    }

    @Test
    @DisplayName("Отправитель, получатели и тема обязательны")
    void requiredFields() {
        assertThatThrownBy(() -> new EmailMessage(null, null, RECIPIENTS, "Тема", null, null, null))
                .overridingErrorMessage("Письмо без отправителя должно отклоняться")
                .isInstanceOf(ServiceValidationException.class);
        assertThatThrownBy(() -> new EmailMessage(FROM, null, null, "Тема", null, null, null))
                .overridingErrorMessage("Письмо без получателей должно отклоняться")
                .isInstanceOf(ServiceValidationException.class);
        assertThatThrownBy(() -> new EmailMessage(FROM, null, RECIPIENTS, null, null, null, null))
                .overridingErrorMessage("Письмо без темы должно отклоняться")
                .isInstanceOf(ServiceValidationException.class);
    }

    @Test
    @DisplayName("Перевод строки в теме запрещён")
    void subjectMustBeSingleLine() {
        assertThatThrownBy(() -> new EmailMessage(FROM, null, RECIPIENTS, "Тема\r\nBcc: x@y.z", null, null, null))
                .overridingErrorMessage("Перевод строки в теме позволил бы дописать в письмо чужие заголовки")
                .isInstanceOf(ServiceValidationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"X-Request-Id", "x-trace", "X-1"})
    @DisplayName("Пользовательские заголовки с префиксом X- разрешены")
    void customHeadersAllowed(String name) {
        assertThatCode(() -> withHeader(name, "значение"))
                .overridingErrorMessage("Заголовок %s должен приниматься", name)
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Subject", "Bcc", "Message-ID", "X-Yamshchik-Email-Id", "x-yamshchik-any", "X Bad", "X-Заголовок"
    })
    @DisplayName("Служебные, зарезервированные и некорректные заголовки запрещены")
    void forbiddenHeaders(String name) {
        assertThatThrownBy(() -> withHeader(name, "значение"))
                .overridingErrorMessage("Заголовок %s не должен приниматься от клиента", name)
                .isInstanceOf(ServiceValidationException.class);
    }

    @Test
    @DisplayName("Перевод строки в значении заголовка запрещён")
    void headerValueMustBeSingleLine() {
        assertThatThrownBy(() -> withHeader("X-Test", "a\nSubject: b"))
                .isInstanceOf(ServiceValidationException.class);
    }

    @Test
    @DisplayName("Письмо не зависит от переданных изменяемых коллекций")
    void defensiveCopies() {
        Map<String, String> headers = new HashMap<>(Map.of("X-Test", "1"));
        EmailMessage message = new EmailMessage(FROM, null, RECIPIENTS, "Тема", null, null, headers);

        headers.put("X-Other", "2");

        assertThat(message.headers())
                .overridingErrorMessage("Изменение исходной карты заголовков не должно менять письмо")
                .containsOnlyKeys("X-Test");
    }

    @Test
    @DisplayName("Персональная отправка: по письму на получателя, остальное содержимое то же")
    void splitByRecipient() {
        Recipients three = new Recipients(
                List.of(mailbox("a@example.org"), mailbox("b@example.org"), mailbox("c@example.org")), null, null);
        EmailMessage message = new EmailMessage(FROM, mailbox("reply@example.org"), three, "Тема",
                                                List.of(BodyAlternative.plainText("текст")), Priority.HIGH,
                                                Map.of("X-Test", "1"));

        List<EmailMessage> personal = message.splitByRecipient();

        assertThat(personal).extracting(EmailMessage::recipients)
                .overridingErrorMessage("Каждое письмо должно идти одному получателю, в порядке списка to")
                .containsExactly(new Recipients(List.of(mailbox("a@example.org")), null, null),
                                 new Recipients(List.of(mailbox("b@example.org")), null, null),
                                 new Recipients(List.of(mailbox("c@example.org")), null, null));
        assertThat(personal)
                .overridingErrorMessage("Письма должны отличаться только получателем")
                .allSatisfy(part -> assertThat(part).usingRecursiveComparison()
                        .ignoringFields("recipients").isEqualTo(message));
    }

    @Test
    @DisplayName("Персональная отправка с копией или скрытой копией запрещена")
    void splitByRecipientRejectsCopies() {
        List<Mailbox> to = List.of(mailbox("a@example.org"));
        List<Mailbox> copy = List.of(mailbox("b@example.org"));

        assertThatThrownBy(() -> message(new Recipients(to, copy, null)).splitByRecipient())
                .overridingErrorMessage("Копия в персональной отправке должна отклоняться")
                .isInstanceOf(ServiceValidationException.class);
        assertThatThrownBy(() -> message(new Recipients(to, null, copy)).splitByRecipient())
                .overridingErrorMessage("Скрытая копия в персональной отправке должна отклоняться")
                .isInstanceOf(ServiceValidationException.class);
    }

    private static EmailMessage message(Recipients recipients) {
        return new EmailMessage(FROM, null, recipients, "Тема", null, null, null);
    }

    private static EmailMessage withHeader(String name, String value) {
        return new EmailMessage(FROM, null, RECIPIENTS, "Тема", null, null, Map.of(name, value));
    }
}
