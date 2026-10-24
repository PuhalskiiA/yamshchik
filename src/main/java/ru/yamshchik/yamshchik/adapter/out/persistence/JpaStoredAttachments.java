package ru.yamshchik.yamshchik.adapter.out.persistence;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.yamshchik.yamshchik.application.port.out.StoredAttachments;
import ru.yamshchik.yamshchik.domain.Attachment;
import ru.yamshchik.yamshchik.domain.StoredAttachment;

import java.time.Instant;
import java.util.List;
import java.util.Optional;


@Component
@RequiredArgsConstructor
public class JpaStoredAttachments implements StoredAttachments {

    private final EmailAttachmentEntityRepository attachmentRepository;

    private final EmailEntityMapper mapper;

    @Override
    public Optional<Attachment> findBySha256(String sha256) {
        return attachmentRepository.findFirstBySha256AndDeletedAtIsNullOrderByIdDesc(sha256).map(mapper::toAttachment);
    }

    @Override
    public long usedBytes() {
        return attachmentRepository.sumStoredBytes();
    }

    @Override
    public List<StoredAttachment> findFinishedBefore(Instant finishedBefore, int limit) {
        return attachmentRepository.findFinishedBefore(finishedBefore, limit).stream().map(mapper::toStored).toList();
    }

    @Override
    public List<StoredAttachment> findFinishedOldest(int limit) {
        return attachmentRepository.findFinishedOldest(limit).stream().map(mapper::toStored).toList();
    }

    @Override
    @Transactional
    public void markDeleted(String storageKey, Instant now) {
        attachmentRepository.markDeleted(storageKey, now);
    }
}
