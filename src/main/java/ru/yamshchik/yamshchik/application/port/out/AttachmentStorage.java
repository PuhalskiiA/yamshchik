package ru.yamshchik.yamshchik.application.port.out;

import ru.yamshchik.yamshchik.domain.Attachment;
import ru.yamshchik.yamshchik.domain.AttachmentContent;

import java.io.InputStream;


/**
 * Хранилище содержимого вложений.
 */
public interface AttachmentStorage {

    /**
     * Сохраняет содержимое под ключом вложения. Сохранённое сверяется с его размером и контрольной суммой.
     */
    void store(Attachment attachment, AttachmentContent content);

    /**
     * @return поток содержимого; закрывает его вызывающий
     */
    InputStream open(Attachment attachment);

    void delete(Attachment attachment);
}
