package ru.yamshchik.yamshchik.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.domain.Persistable;
import ru.yamshchik.yamshchik.domain.DeliveryStatus;
import ru.yamshchik.yamshchik.domain.ReportAction;

import java.time.Instant;
import java.util.UUID;


@Entity
@Table(name = "delivery_event")
@Getter
@Setter
@NoArgsConstructor
public class DeliveryEventEntity implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID emailId;

    @Column(nullable = false)
    private UUID batchId;

    @Column(nullable = false)
    private String recipient;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DeliveryStatus outcome;

    @Enumerated(EnumType.STRING)
    private ReportAction action;

    private String status;

    private String diagnostic;

    @Column(nullable = false)
    private Instant occurredAt;

    private Instant claimedUntil;

    private Instant publishedAt;

    // Идентификатор назначает приложение, поэтому по нему Spring Data не отличит новую запись от существующей
    @Transient
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private boolean persisted;

    @Override
    public boolean isNew() {
        return !persisted;
    }

    @PostPersist
    @PostLoad
    void markPersisted() {
        persisted = true;
    }
}
