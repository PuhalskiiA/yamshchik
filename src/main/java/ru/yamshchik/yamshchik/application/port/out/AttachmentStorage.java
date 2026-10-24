package ru.yamshchik.yamshchik.application.port.out;

import ru.yamshchik.yamshchik.domain.Attachment;
import ru.yamshchik.yamshchik.domain.AttachmentContent;

import java.io.InputStream;


/**
 * Хранилище содержимого вложений.
 */
public interface AttachmentStorage {

    /**
     * Сохраняет содержимое вложения.
     *
     * @return ключ, под которым содержимое сохранено: предложенный в {@code attachment} или свой,
     *         если хранилище само назначает место
     */
    String store(Attachment attachment, AttachmentContent content);

    /**
     * @return поток содержимого; закрывает его вызывающий
     */
    InputStream open(Attachment attachment);

    void delete(Attachment attachment);
}
