package ru.yamshchik.yamshchik.config.exception.type;

import lombok.Getter;


/**
 * Содержимое вложения не удалось прочитать из хранилища.
 */
@Getter
public class AttachmentStorageException extends RuntimeException {

    /**
     * Хранилище недоступно, а не содержимое отсутствует: повторное чтение может пройти.
     */
    private final boolean temporary;

    public AttachmentStorageException(String message, boolean temporary, Throwable cause) {
        super(message, cause);
        this.temporary = temporary;
    }
}
