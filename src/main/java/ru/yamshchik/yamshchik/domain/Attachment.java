package ru.yamshchik.yamshchik.domain;

import ru.yamshchik.yamshchik.config.exception.type.ServiceValidationException;


/**
 * Вложение письма. Содержимое лежит в хранилище вложений, здесь — только ссылка на него.
 *
 * @param storageKey ключ содержимого в хранилище
 * @param size       размер содержимого в байтах
 * @param sha256     контрольная сумма содержимого в шестнадцатеричном виде
 */
public record Attachment(
        String filename,
        String mediaType,
        AttachmentDisposition disposition,
        String contentId,
        String storageKey,
        long size,
        String sha256
) {

    public Attachment {
        if (filename == null || filename.isBlank()) {
            throw new ServiceValidationException("Attachment filename is required");
        }
        if (mediaType == null || mediaType.isBlank()) {
            throw new ServiceValidationException("Attachment media type is required: " + filename);
        }
        if (disposition == null) {
            throw new ServiceValidationException("Attachment disposition is required: " + filename);
        }
        if (disposition == AttachmentDisposition.INLINE && (contentId == null || contentId.isBlank())) {
            throw new ServiceValidationException("Inline attachment requires contentId: " + filename);
        }
        if (storageKey == null || storageKey.isBlank() || sha256 == null || sha256.isBlank() || size < 0) {
            throw new ServiceValidationException("Attachment content reference is invalid: " + filename);
        }
        HeaderRules.requireSingleLine("attachment filename", filename);
        HeaderRules.requireSingleLine("attachment media type", mediaType);
        HeaderRules.requireSingleLine("attachment contentId", contentId);
    }
}
