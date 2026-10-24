package ru.yamshchik.yamshchik.config.exception.type;

import lombok.Getter;

import java.util.List;


/**
 * Файлы части вложений не присланы, а вложений с такими контрольными суммами у сервиса нет.
 */
@Getter
public class MissingAttachmentsException extends RuntimeException {

    /**
     * Контрольные суммы вложений, файлы которых клиент должен приложить.
     */
    private final List<String> missing;

    public MissingAttachmentsException(List<String> missing) {
        super("Attachment files must be sent, no stored attachments with these checksums: " + missing);
        this.missing = List.copyOf(missing);
    }
}
