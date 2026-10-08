package ru.yamshchik.yamshchik.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import ru.yamshchik.yamshchik.domain.AttachmentDisposition;


@Entity
@Table(name = "email_attachment")
@Getter
@Setter
@NoArgsConstructor
public class EmailAttachmentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "email_id", nullable = false)
    private EmailEntity email;

    @Column(nullable = false)
    private int position;

    @Column(nullable = false)
    private String filename;

    @Column(nullable = false)
    private String mediaType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AttachmentDisposition disposition;

    private String contentId;

    @Column(nullable = false)
    private String storageKey;

    @Column(nullable = false)
    private long sizeBytes;

    @Column(nullable = false)
    private String sha256;
}
