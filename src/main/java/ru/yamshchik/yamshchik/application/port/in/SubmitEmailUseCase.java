package ru.yamshchik.yamshchik.application.port.in;

import ru.yamshchik.yamshchik.domain.AttachmentContent;
import ru.yamshchik.yamshchik.domain.AttachmentDisposition;
import ru.yamshchik.yamshchik.domain.EmailMessage;
import ru.yamshchik.yamshchik.domain.EmailState;

import java.util.List;


/**
 * Приём письма на отправку.
 */
public interface SubmitEmailUseCase {

    /**
     * Проверяет письмо, сохраняет его и ставит в очередь.
     *
     * @return состояние принятого письма
     */
    EmailState submit(SubmitEmailCommand command);

    record SubmitEmailCommand(EmailMessage message, List<AttachmentUpload> attachments) {

        public SubmitEmailCommand {
            attachments = attachments == null ? List.of() : List.copyOf(attachments);
        }
    }

    /**
     * Загружаемое вложение: его содержимое ещё не сохранено.
     */
    record AttachmentUpload(
            String filename,
            String mediaType,
            AttachmentDisposition disposition,
            String contentId,
            long size,
            AttachmentContent content
    ) {
    }
}
