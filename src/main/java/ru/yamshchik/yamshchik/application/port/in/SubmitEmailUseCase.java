package ru.yamshchik.yamshchik.application.port.in;

import ru.yamshchik.yamshchik.domain.AttachmentContent;
import ru.yamshchik.yamshchik.domain.AttachmentDisposition;
import ru.yamshchik.yamshchik.domain.EmailMessage;

import java.util.List;


/**
 * Приём письма на отправку.
 */
public interface SubmitEmailUseCase {

    /**
     * Проверяет письмо, сохраняет его и ставит в очередь. Запрос принимается целиком или не принимается вовсе.
     *
     * @return принятые письма: одно, а при персональной отправке — по одному на получателя
     */
    List<EmailView> submit(SubmitEmailCommand command);

    /**
     * @param personal каждому получателю уходит отдельное письмо
     */
    record SubmitEmailCommand(EmailMessage message, List<AttachmentUpload> attachments, boolean personal) {

        public SubmitEmailCommand {
            attachments = attachments == null ? List.of() : List.copyOf(attachments);
        }
    }

    /**
     * Вложение из запроса. Файл может быть не прислан — тогда вложение берётся из ранее принятых
     * по контрольной сумме.
     *
     * @param sha256    контрольная сумма содержимого, заявленная клиентом
     * @param mediaType тип присланного файла; null, если файл не прислан
     * @param size      размер присланного файла; 0, если файл не прислан
     * @param content   содержимое присланного файла; null, если файл не прислан
     */
    record AttachmentUpload(
            String filename,
            String mediaType,
            AttachmentDisposition disposition,
            String contentId,
            String sha256,
            long size,
            AttachmentContent content
    ) {

        public boolean hasContent() {
            return content != null;
        }
    }
}
