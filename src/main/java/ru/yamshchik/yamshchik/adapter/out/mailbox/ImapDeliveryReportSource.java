package ru.yamshchik.yamshchik.adapter.out.mailbox;

import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.search.FlagTerm;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.mail.autoconfigure.MailProperties;
import org.springframework.stereotype.Component;
import ru.yamshchik.yamshchik.application.port.out.DeliveryReportSource;
import ru.yamshchik.yamshchik.config.exception.type.ServiceException;
import ru.yamshchik.yamshchik.config.properties.ReportProperties;
import ru.yamshchik.yamshchik.config.properties.YamshchikProperties;
import ru.yamshchik.yamshchik.domain.DeliveryReport;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.function.Predicate;


/**
 * Ящик учётной записи сервиса на сервере IMAP. Обработанное письмо помечается прочитанным и остаётся
 * в ящике; читаются только непрочитанные.
 */
@Component
@Slf4j
public class ImapDeliveryReportSource implements DeliveryReportSource {

    private static final String PROTOCOL = "imap";

    private static final String TIMEOUT_MILLIS = "10000";

    private static final List<String> TIMEOUT_PROPERTIES = List.of("mail.imap.connectiontimeout",
                                                                   "mail.imap.timeout",
                                                                   "mail.imap.writetimeout");

    // Без этого сервер сам помечает письмо прочитанным при чтении содержимого, и отложенный отчёт больше не читается
    private static final String PEEK_PROPERTY = "mail.imap.peek";

    private final DeliveryReportParser parser;

    private final ReportProperties reports;

    private final String username;

    private final String password;

    private final Session session;

    // Учётная запись та же, что для отправки: от неё уходят письма, на неё и приходят отчёты
    public ImapDeliveryReportSource(
            DeliveryReportParser parser,
            YamshchikProperties properties,
            MailProperties mailProperties
    ) {
        this.parser = parser;
        this.reports = properties.getReports();
        this.username = mailProperties.getUsername();
        this.password = mailProperties.getPassword();
        Properties sessionProperties = new Properties();
        TIMEOUT_PROPERTIES.forEach(name -> sessionProperties.setProperty(name, TIMEOUT_MILLIS));
        sessionProperties.setProperty(PEEK_PROPERTY, Boolean.TRUE.toString());
        this.session = Session.getInstance(sessionProperties);
    }

    @Override
    public int poll(int limit, Predicate<List<DeliveryReport>> handler) {
        try (Store store = session.getStore(PROTOCOL)) {
            store.connect(reports.getHost(), reports.getPort(), username, password);
            Folder folder = store.getFolder(reports.getFolder());
            folder.open(Folder.READ_WRITE);
            try {
                Message[] unread = folder.search(new FlagTerm(new Flags(Flags.Flag.SEEN), false));
                Message[] batch = Arrays.copyOf(unread, Math.min(limit, unread.length));
                for (Message message : batch) {
                    if (handler.test(reportsOf(message))) {
                        message.setFlag(Flags.Flag.SEEN, true);
                    }
                }
                return batch.length;
            } finally {
                folder.close(false);
            }
        } catch (MessagingException e) {
            throw new ServiceException("Failed to read delivery reports: " + e.getMessage(), e);
        }
    }

    // Письмо, которое не удаётся разобрать, отчётом не считается: иначе оно читалось бы бесконечно
    private List<DeliveryReport> reportsOf(Message message) {
        try {
            return parser.parse(message);
        } catch (IOException | MessagingException | RuntimeException e) {
            log.warn("Message {} in the report mailbox cannot be parsed and is skipped",
                     message.getMessageNumber(), e);
            return List.of();
        }
    }
}
