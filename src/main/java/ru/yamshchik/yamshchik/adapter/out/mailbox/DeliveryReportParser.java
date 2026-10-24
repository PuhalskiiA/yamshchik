package ru.yamshchik.yamshchik.adapter.out.mailbox;

import jakarta.mail.BodyPart;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.InternetHeaders;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.yamshchik.yamshchik.adapter.out.smtp.MimeMessageAssembler;
import ru.yamshchik.yamshchik.domain.Delivery;
import ru.yamshchik.yamshchik.domain.DeliveryReport;
import ru.yamshchik.yamshchik.domain.EmailId;
import ru.yamshchik.yamshchik.domain.ReportAction;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;


/**
 * Разбирает отчёт о доставке (RFC 3464, RFC 6533): письмо {@code multipart/report} с частью статусов
 * по каждому получателю и заголовками исходного письма. Исходное письмо узнаётся по заголовкам,
 * которые ставит {@link MimeMessageAssembler}.
 */
@Component
@Slf4j
public class DeliveryReportParser {

    private static final String REPORT_TYPE = "multipart/report";

    // Отчёт о письме с адресами в UTF-8 приходит в «глобальных» типах частей
    private static final List<String> STATUS_TYPES = List.of("message/delivery-status",
                                                             "message/global-delivery-status");

    private static final List<String> ORIGINAL_TYPES = List.of("text/rfc822-headers",
                                                               "message/global-headers",
                                                               "message/rfc822",
                                                               "message/global");

    private static final String ORIGINAL_RECIPIENT = "Original-Recipient";

    private static final String FINAL_RECIPIENT = "Final-Recipient";

    private static final String ACTION = "Action";

    private static final String STATUS = "Status";

    private static final String DIAGNOSTIC_CODE = "Diagnostic-Code";

    // Адрес в поле получателя идёт после типа адреса: «rfc822; user@example.org»
    private static final char ADDRESS_TYPE_SEPARATOR = ';';

    private static final String WHITESPACE = "\\s+";

    private static final String SPACE = " ";

    /**
     * @return отчёты по получателям; пусто, если письмо не отчёт о доставке или отчёт не о нашем письме
     */
    public List<DeliveryReport> parse(Part message) throws MessagingException, IOException {
        if (!message.isMimeType(REPORT_TYPE) || !(message.getContent() instanceof Multipart report)) {
            return List.of();
        }
        List<InternetHeaders> recipientFields = new ArrayList<>();
        InternetHeaders original = null;
        for (int index = 0; index < report.getCount(); index++) {
            BodyPart part = report.getBodyPart(index);
            if (isAnyOf(part, STATUS_TYPES)) {
                recipientFields.addAll(readRecipientFields(part));
            } else if (isAnyOf(part, ORIGINAL_TYPES)) {
                try (InputStream input = part.getInputStream()) {
                    original = new InternetHeaders(input);
                }
            }
        }
        if (original == null) {
            return List.of();
        }

        EmailId emailId = parseEmailId(original.getHeader(MimeMessageAssembler.EMAIL_ID_HEADER, null));
        Integer attempt = parseInt(original.getHeader(MimeMessageAssembler.ATTEMPT_HEADER, null));
        if (emailId == null || attempt == null) {
            return List.of();
        }
        List<DeliveryReport> reports = new ArrayList<>();
        for (InternetHeaders fields : recipientFields) {
            String recipient = recipient(fields);
            ReportAction action = parseAction(fields.getHeader(ACTION, null));
            if (recipient == null || action == null) {
                log.warn("Delivery report for email {} has a recipient block without address or action", emailId);
                continue;
            }
            reports.add(new DeliveryReport(emailId, attempt, recipient, action,
                                           normalized(fields.getHeader(STATUS, null)),
                                           normalized(fields.getHeader(DIAGNOSTIC_CODE, null))));
        }
        return reports;
    }

    // Поля отчёта идут блоками через пустую строку: первый блок — о письме в целом, остальные — по получателям
    private static List<InternetHeaders> readRecipientFields(Part part) throws MessagingException, IOException {
        List<InternetHeaders> blocks = new ArrayList<>();
        try (InputStream input = part.getInputStream()) {
            new InternetHeaders(input);
            InternetHeaders block = new InternetHeaders(input);
            while (block.getAllHeaders().hasMoreElements()) {
                blocks.add(block);
                block = new InternetHeaders(input);
            }
        }
        return blocks;
    }

    // Исходный адрес точнее итогового: сервер мог переписать адрес при пересылке
    private static String recipient(InternetHeaders fields) {
        String value = fields.getHeader(ORIGINAL_RECIPIENT, null);
        if (value == null) {
            value = fields.getHeader(FINAL_RECIPIENT, null);
        }
        if (value == null) {
            return null;
        }
        return Delivery.recipientKey(value.substring(value.indexOf(ADDRESS_TYPE_SEPARATOR) + 1));
    }

    private static boolean isAnyOf(Part part, List<String> types) throws MessagingException {
        for (String type : types) {
            if (part.isMimeType(type)) {
                return true;
            }
        }
        return false;
    }

    private static EmailId parseEmailId(String value) {
        try {
            return value == null ? null : new EmailId(UUID.fromString(value.trim()));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Integer parseInt(String value) {
        try {
            return value == null ? null : Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static ReportAction parseAction(String value) {
        try {
            return value == null ? null : ReportAction.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // Длинные поля отчёта переносятся на следующие строки — собираем их в одну
    private static String normalized(String value) {
        return value == null ? null : value.replaceAll(WHITESPACE, SPACE).trim();
    }
}
