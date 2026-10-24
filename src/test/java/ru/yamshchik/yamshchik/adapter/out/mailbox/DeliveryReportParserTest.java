package ru.yamshchik.yamshchik.adapter.out.mailbox;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.yamshchik.yamshchik.domain.DeliveryReport;
import ru.yamshchik.yamshchik.domain.EmailId;
import ru.yamshchik.yamshchik.domain.ReportAction;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;


@DisplayName("Разбор отчёта о доставке")
class DeliveryReportParserTest {

    private static final EmailId EMAIL_ID = new EmailId(UUID.fromString("6b85413e-1c64-4b9b-a7f9-073133351c09"));

    // Отчёт Postfix об успехе по двум получателям: своему ящику и чужому домену, переданному дальше
    private static final String SUCCESS_REPORT = """
            From: Mail Delivery System <MAILER-DAEMON@yamshchik.test>
            To: noreply@yamshchik.test
            Subject: Successful Mail Delivery Report
            MIME-Version: 1.0
            Content-Type: multipart/report; report-type=delivery-status;
            \tboundary="BOUNDARY"

            --BOUNDARY
            Content-Type: text/plain; charset=us-ascii

            This is the mail system at host mail.yamshchik.test.

            --BOUNDARY
            Content-Type: message/delivery-status

            Reporting-MTA: dns; mail.yamshchik.test
            Arrival-Date: Sat, 24 Oct 2026 12:11:13 +0000

            Final-Recipient: rfc822; user@yamshchik.test
            Original-Recipient: rfc822;user@yamshchik.test
            Action: delivered
            Status: 2.0.0
            Diagnostic-Code: X-Postfix; delivery via virtual: delivered to maildir

            Final-Recipient: rfc822; someone@example.org
            Original-Recipient: rfc822;someone@example.org
            Action: relayed
            Status: 2.0.0
            Diagnostic-Code: smtp; 250 2.0.0 Ok: queued as GvcyvDZb2G

            --BOUNDARY
            Content-Type: text/rfc822-headers

            Message-ID: <6b85413e-1c64-4b9b-a7f9-073133351c09@yamshchik.test>
            Subject: Отчёт готов
            X-Yamshchik-Email-Id: 6b85413e-1c64-4b9b-a7f9-073133351c09
            X-Yamshchik-Attempt: 2

            --BOUNDARY--
            """;

    // Отказ в «глобальных» типах частей, с длинным ответом сервера на двух строках и без исходного адреса
    private static final String FAILURE_REPORT = """
            From: Mail Delivery System <MAILER-DAEMON@yamshchik.test>
            Subject: Undelivered Mail Returned to Sender
            MIME-Version: 1.0
            Content-Type: multipart/report; report-type=delivery-status; boundary="B"

            --B
            Content-Type: text/plain; charset=utf-8

            I'm sorry to have to inform you that your message could not be delivered.

            --B
            Content-Type: message/global-delivery-status

            Reporting-MTA: dns; mail.yamshchik.test

            Final-Recipient: rfc822; Slow@Example.org
            Action: failed
            Status: 4.0.0
            Diagnostic-Code: smtp; 451 Chaos recipient error
             (in reply to RCPT TO command)

            --B
            Content-Type: message/global-headers

            Message-ID: <6b85413e-1c64-4b9b-a7f9-073133351c09@yamshchik.test>
            X-Yamshchik-Email-Id: 6b85413e-1c64-4b9b-a7f9-073133351c09
            X-Yamshchik-Attempt: 1

            --B--
            """;

    private final DeliveryReportParser parser = new DeliveryReportParser();

    @Test
    @DisplayName("Отчёт по нескольким получателям даёт отчёт на каждого, с письмом и номером попытки")
    void successReport() throws Exception {
        List<DeliveryReport> reports = parser.parse(message(SUCCESS_REPORT));

        assertThat(reports)
                .overridingErrorMessage("По каждому получателю из отчёта должен быть свой отчёт, получено: %s", reports)
                .extracting(DeliveryReport::recipient, DeliveryReport::action, DeliveryReport::status)
                .containsExactly(tuple("user@yamshchik.test", ReportAction.DELIVERED, "2.0.0"),
                                 tuple("someone@example.org", ReportAction.RELAYED, "2.0.0"));
        assertThat(reports).allSatisfy(report -> {
            assertThat(report.emailId())
                    .overridingErrorMessage("Письмо должно узнаваться по заголовку из исходного письма")
                    .isEqualTo(EMAIL_ID);
            assertThat(report.attempt())
                    .overridingErrorMessage("Номер попытки нужен, чтобы отсечь отчёт о прошлой попытке")
                    .isEqualTo(2);
        });
    }

    @Test
    @DisplayName("Отчёт в глобальных типах частей разбирается; длинный ответ собирается в одну строку")
    void globalFailureReport() throws Exception {
        List<DeliveryReport> reports = parser.parse(message(FAILURE_REPORT));

        assertThat(reports).singleElement().satisfies(report -> {
            assertThat(report.recipient())
                    .overridingErrorMessage("Адрес без исходного получателя берётся из итогового и приводится к одному виду")
                    .isEqualTo("slow@example.org");
            assertThat(report.action()).isEqualTo(ReportAction.FAILED);
            assertThat(report.temporaryFailure()).isTrue();
            assertThat(report.diagnostic()).isEqualTo("smtp; 451 Chaos recipient error (in reply to RCPT TO command)");
            assertThat(report.attempt()).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("Обычное письмо в ящике — не отчёт")
    void plainMessage() throws Exception {
        String reply = """
                From: user@example.org
                Subject: Re: Отчёт готов
                Content-Type: text/plain; charset=utf-8

                Спасибо!
                """;

        assertThat(parser.parse(message(reply)))
                .overridingErrorMessage("Ответ человека не должен приниматься за отчёт о доставке")
                .isEmpty();
    }

    @Test
    @DisplayName("Отчёт о чужом письме пропускается")
    void reportAboutForeignEmail() throws Exception {
        String foreign = SUCCESS_REPORT
                .replace("X-Yamshchik-Email-Id: 6b85413e-1c64-4b9b-a7f9-073133351c09\n", "")
                .replace("X-Yamshchik-Attempt: 2\n", "");

        assertThat(parser.parse(message(foreign)))
                .overridingErrorMessage("Без наших заголовков отчёт нельзя отнести ни к одному письму")
                .isEmpty();
    }

    private static MimeMessage message(String text) throws Exception {
        return new MimeMessage(Session.getInstance(new Properties()),
                               new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }
}
