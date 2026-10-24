package ru.yamshchik.yamshchik.application.port.out;

import ru.yamshchik.yamshchik.domain.Attachment;
import ru.yamshchik.yamshchik.domain.StoredAttachment;

import java.time.Instant;
import java.util.List;
import java.util.Optional;


/**
 * Учёт содержимого вложений, лежащего в хранилище: по нему уборка решает, что удалять.
 */
public interface StoredAttachments {

    /**
     * @return ранее принятое вложение с таким содержимым, если оно ещё лежит в хранилище
     */
    Optional<Attachment> findBySha256(String sha256);

    /**
     * @return сколько байт занимает содержимое вложений, которое ещё не удалено
     */
    long usedBytes();

    /**
     * @return содержимое, все письма которого завершены раньше {@code finishedBefore}: отправлены
     *         или окончательно не отправлены
     */
    List<StoredAttachment> findFinishedBefore(Instant finishedBefore, int limit);

    /**
     * Содержимое, которое ещё может понадобиться для отправки, сюда не попадает никогда.
     *
     * @return содержимое, все письма которого завершены, от давно завершённых к недавним
     */
    List<StoredAttachment> findFinishedOldest(int limit);

    /**
     * Отмечает содержимое удалённым у всех писем, которым оно принадлежит.
     */
    void markDeleted(String storageKey, Instant now);
}
