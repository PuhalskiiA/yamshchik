package ru.yamshchik.yamshchik.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import ru.yamshchik.yamshchik.domain.DeliveryStatus;
import ru.yamshchik.yamshchik.domain.Priority;
import ru.yamshchik.yamshchik.domain.ReportAction;

import java.time.Instant;
import java.util.UUID;


@Entity
@Table(name = "email_delivery")
@Getter
@Setter
@NoArgsConstructor
public class DeliveryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private UUID emailId;

    @Column(nullable = false)
    private UUID batchId;

    @Column(nullable = false)
    private String recipient;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Priority priority;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DeliveryStatus status;

    @Column(nullable = false)
    private int attempt;

    @Enumerated(EnumType.STRING)
    private ReportAction reportAction;

    private String reportStatus;

    private String diagnostic;

    @Column(nullable = false)
    private Instant updatedAt;

    private Instant sentAt;

    private Instant incidentAt;
}
