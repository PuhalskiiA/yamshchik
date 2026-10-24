package ru.yamshchik.yamshchik.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.yamshchik.yamshchik.TestData;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ru.yamshchik.yamshchik.TestData.NOW;


@DisplayName("Доставка письма получателю")
class DeliveryTest {

    private static final EmailId EMAIL_ID = EmailId.newId();

    private static final String RECIPIENT = "user@example.org";

    @Test
    @DisplayName("Адрес получателя приводится к одному виду: регистр и пробелы роли не играют")
    void recipientKey() {
        assertThat(Delivery.recipientKey("  User@Example.ORG "))
                .overridingErrorMessage("Отчёт с адресом в другом регистре должен находить ту же доставку")
                .isEqualTo(RECIPIENT);
    }

    @Test
    @DisplayName("Принятая сервером доставка ждёт отчёта о своей попытке")
    void sent() {
        Delivery sent = TestData.pendingDelivery(EMAIL_ID, RECIPIENT).sent(2, NOW.plusSeconds(5));

        assertThat(sent.status()).isEqualTo(DeliveryStatus.SENT);
        assertThat(sent.attempt())
                .overridingErrorMessage("Доставка должна помнить попытку, о которой ждёт отчёта")
                .isEqualTo(2);
        assertThat(sent.sentAt()).isEqualTo(NOW.plusSeconds(5));
    }

    @Test
    @DisplayName("Отчёт о доставке и о передаче дальше — окончательный исход DELIVERED")
    void delivered() {
        Delivery delivered = sent(1).delivered(report(1, ReportAction.RELAYED, "2.0.0"), NOW);

        assertThat(delivered.status()).isEqualTo(DeliveryStatus.DELIVERED);
        assertThat(delivered.isFinal()).isTrue();
        assertThat(delivered.reportAction())
                .overridingErrorMessage("Передача дальше должна быть видна: судьба письма после неё неизвестна")
                .isEqualTo(ReportAction.RELAYED);
        assertThat(delivered.reportStatus()).isEqualTo("2.0.0");
    }

    @Test
    @DisplayName("Отчёт о задержке статус не меняет, но запоминается")
    void delayed() {
        Delivery delayed = sent(1).delayed(report(1, ReportAction.DELAYED, "4.0.0"), NOW);

        assertThat(delayed.status())
                .overridingErrorMessage("Сервер ещё доставляет сам: доставка должна по-прежнему ждать отчёта")
                .isEqualTo(DeliveryStatus.SENT);
        assertThat(delayed.reportAction()).isEqualTo(ReportAction.DELAYED);
    }

    @Test
    @DisplayName("Повтор возвращает доставку к ожиданию отправки, а новая отправка сбрасывает прошлый отчёт")
    void retryThenSentAgain() {
        Delivery retried = sent(1).retry(report(1, ReportAction.FAILED, "4.0.0"), NOW);
        Delivery sentAgain = retried.sent(2, NOW.plusSeconds(60));

        assertThat(retried.status()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(sentAgain.reportAction())
                .overridingErrorMessage("Отчёт о прошлой попытке не должен выдаваться за отчёт о новой")
                .isNull();
        assertThat(sentAgain.attempt()).isEqualTo(2);
    }

    @Test
    @DisplayName("Отчёт о другой попытке или другом получателе не применяется")
    void foreignReportIsRejected() {
        Delivery sent = sent(2);

        assertThatThrownBy(() -> sent.delivered(report(1, ReportAction.DELIVERED, "2.0.0"), NOW))
                .overridingErrorMessage("Отчёт о первой попытке не должен закрывать доставку второй")
                .isInstanceOf(IllegalStateException.class);
        DeliveryReport otherRecipient = new DeliveryReport(EMAIL_ID, 2, "other@example.org", ReportAction.DELIVERED,
                                                           "2.0.0", null);
        assertThatThrownBy(() -> sent.delivered(otherRecipient, NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Отчёт к доставке, которая его не ждёт, не применяется")
    void reportBeforeSendingIsRejected() {
        Delivery pending = TestData.pendingDelivery(EMAIL_ID, RECIPIENT);

        assertThatThrownBy(() -> pending.delivered(report(0, ReportAction.DELIVERED, "2.0.0"), NOW))
                .overridingErrorMessage("Письмо, которое ещё не ушло, не может быть доставлено")
                .isInstanceOf(IllegalStateException.class);
        Delivery delivered = sent(1).delivered(report(1, ReportAction.DELIVERED, "2.0.0"), NOW);
        assertThatThrownBy(() -> delivered.undelivered(report(1, ReportAction.FAILED, "5.0.0"), NOW))
                .overridingErrorMessage("Окончательный исход не должен меняться")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Доставка восстанавливается по отчёту только о более новой попытке")
    void recovered() {
        Delivery recovered = TestData.pendingDelivery(EMAIL_ID, RECIPIENT).recovered(1, NOW);

        assertThat(recovered.status()).isEqualTo(DeliveryStatus.SENT);
        assertThat(recovered.attempt()).isEqualTo(1);
        Delivery retried = sent(1).retry(report(1, ReportAction.FAILED, "4.0.0"), NOW);
        assertThatThrownBy(() -> retried.recovered(1, NOW))
                .overridingErrorMessage("Повтор того же отчёта о попытке, после которой доставка ждёт повтора, не восстанавливает её")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Отказ при отправке закрывает доставку без отчёта")
    void undeliveredWithoutReport() {
        Delivery undelivered = TestData.pendingDelivery(EMAIL_ID, RECIPIENT).undelivered("550 no such user", NOW);

        assertThat(undelivered.status()).isEqualTo(DeliveryStatus.UNDELIVERED);
        assertThat(undelivered.diagnostic()).isEqualTo("550 no such user");
        assertThat(undelivered.reportAction()).isNull();
    }

    @Test
    @DisplayName("Инцидент заводится один раз и только по доставке, ждущей отчёта")
    void incident() {
        Delivery incident = sent(1).incident(NOW.plusSeconds(600));

        assertThat(incident.incidentAt()).isEqualTo(NOW.plusSeconds(600));
        assertThat(incident.status()).isEqualTo(DeliveryStatus.SENT);
        assertThatThrownBy(() -> incident.incident(NOW.plusSeconds(700)))
                .overridingErrorMessage("Повторный инцидент по той же доставке заводиться не должен")
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> TestData.pendingDelivery(EMAIL_ID, RECIPIENT).incident(NOW))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Событие создаётся только об окончательном исходе и несёт пачку и отчёт")
    void event() {
        Delivery undelivered = sent(1).undelivered(report(1, ReportAction.FAILED, "5.1.1"), NOW);

        DeliveryEvent event = DeliveryEvent.of(undelivered);

        assertThat(event.outcome()).isEqualTo(DeliveryStatus.UNDELIVERED);
        assertThat(event.batchId()).isEqualTo(TestData.BATCH_ID);
        assertThat(event.action()).isEqualTo(ReportAction.FAILED);
        assertThat(event.status()).isEqualTo("5.1.1");
        assertThatThrownBy(() -> DeliveryEvent.of(sent(1)))
                .overridingErrorMessage("О доставке, ждущей отчёта, событий быть не должно")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Временным считается только окончательный отказ с классом статуса 4")
    void temporaryFailure() {
        assertThat(report(1, ReportAction.FAILED, "4.4.1").temporaryFailure()).isTrue();
        assertThat(report(1, ReportAction.FAILED, "5.1.1").temporaryFailure()).isFalse();
        assertThat(report(1, ReportAction.DELAYED, "4.4.1").temporaryFailure())
                .overridingErrorMessage("Задержка — не отказ: сервер ещё доставляет сам")
                .isFalse();
        assertThat(report(1, ReportAction.FAILED, null).temporaryFailure()).isFalse();
    }

    private static Delivery sent(int attempt) {
        return TestData.sentDelivery(EMAIL_ID, RECIPIENT, attempt);
    }

    private static DeliveryReport report(int attempt, ReportAction action, String status) {
        return new DeliveryReport(EMAIL_ID, attempt, RECIPIENT, action, status, "smtp; " + status);
    }
}
