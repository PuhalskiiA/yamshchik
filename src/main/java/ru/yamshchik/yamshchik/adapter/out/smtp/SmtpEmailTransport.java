package ru.yamshchik.yamshchik.adapter.out.smtp;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.eclipse.angus.mail.smtp.SMTPSenderFailedException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import ru.yamshchik.yamshchik.application.port.out.EmailTransport;
import ru.yamshchik.yamshchik.config.exception.type.AttachmentStorageException;
import ru.yamshchik.yamshchik.config.exception.type.EmailTransportException;
import ru.yamshchik.yamshchik.config.exception.type.ServiceValidationException;
import ru.yamshchik.yamshchik.domain.DeliveryFailure;
import ru.yamshchik.yamshchik.domain.Email;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


@Component
@RequiredArgsConstructor
public class SmtpEmailTransport implements EmailTransport {

    private static final String ASSEMBLY_ERROR_MESSAGE = "Email cannot be assembled: ";

    private static final int FIRST_TEMPORARY_CODE = 400;

    private static final int FIRST_PERMANENT_CODE = 500;

    // Расширенный статус по RFC 3463: класс.предмет.деталь, например 4.7.1
    private static final Pattern ENHANCED_STATUS_PATTERN = Pattern.compile("\\b[245]\\.\\d{1,3}\\.\\d{1,3}\\b");

    private final JavaMailSender mailSender;

    private final MimeMessageAssembler assembler;

    @Override
    public void send(Email email) {
        MimeMessage mimeMessage = mailSender.createMimeMessage();
        try {
            assembler.assemble(email, mimeMessage);
        } catch (Exception e) {
            // Письмо, которое не собирается, не соберётся и при повторе
            throw new EmailTransportException(DeliveryFailure.permanent(e.getMessage()), e);
        }
        try {
            mailSender.send(mimeMessage);
        } catch (Exception e) {
            // Любой сбой передачи — это результат отправки, а не ошибка обработчика очереди
            throw new EmailTransportException(classify(e), e);
        }
    }

    @Override
    public void verify(Email email) {
        try {
            assembler.assemble(email, mailSender.createMimeMessage());
        } catch (Exception e) {
            throw new ServiceValidationException(ASSEMBLY_ERROR_MESSAGE + e.getMessage());
        }
    }

    /**
     * Постоянный отказ — ответ сервера 5xx хотя бы по одному получателю или пропавшее вложение.
     * Всё остальное, включая сбой соединения и ошибку входа, считается временным: письмо не должно
     * пропасть из-за недоступности или неверной настройки сервера.
     */
    private static DeliveryFailure classify(Exception error) {
        Deque<Throwable> pending = new ArrayDeque<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        pending.add(error);

        Integer temporaryCode = null;
        String temporaryStatus = null;
        while (!pending.isEmpty()) {
            Throwable current = pending.poll();
            if (!seen.add(current)) {
                continue;
            }
            if (current instanceof AttachmentStorageException storage) {
                return new DeliveryFailure(storage.isTemporary(), null, null, error.getMessage());
            }
            Integer code = replyCode(current);
            if (code != null && code >= FIRST_PERMANENT_CODE) {
                return new DeliveryFailure(false, code, enhancedStatus(current), error.getMessage());
            }
            if (code != null && code >= FIRST_TEMPORARY_CODE && temporaryCode == null) {
                temporaryCode = code;
                temporaryStatus = enhancedStatus(current);
            }

            addIfPresent(pending, current.getCause());
            if (current instanceof MessagingException messaging) {
                addIfPresent(pending, messaging.getNextException());
            }
            if (current instanceof MailSendException sendException) {
                Collections.addAll(pending, sendException.getMessageExceptions());
            }
        }
        return new DeliveryFailure(true, temporaryCode, temporaryStatus, error.getMessage());
    }

    private static void addIfPresent(Deque<Throwable> pending, Throwable error) {
        if (error != null) {
            pending.add(error);
        }
    }

    private static Integer replyCode(Throwable error) {
        return switch (error) {
            case SMTPSendFailedException failed -> failed.getReturnCode();
            case SMTPAddressFailedException failed -> failed.getReturnCode();
            case SMTPSenderFailedException failed -> failed.getReturnCode();
            default -> null;
        };
    }

    private static String enhancedStatus(Throwable error) {
        if (error.getMessage() == null) {
            return null;
        }
        Matcher matcher = ENHANCED_STATUS_PATTERN.matcher(error.getMessage());
        return matcher.find() ? matcher.group() : null;
    }
}
