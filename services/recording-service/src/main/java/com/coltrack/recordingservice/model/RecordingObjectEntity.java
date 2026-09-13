package com.coltrack.recordingservice.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "recording_objects")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecordingObjectEntity {

    @Id
    private UUID id;

    @Column(name = "recording_id", nullable = false)
    private UUID recordingId;

    @Column(name = "s3_key", nullable = false, length = 1024)
    private String s3Key;

    @Column(name = "file_name")
    private String fileName;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "sequence_number")
    private Integer sequenceNumber;

    @Column(name = "uploaded_at")
    private Instant uploadedAt;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "cleanup_status", length = 32)
    private S3ObjectCleanupStatus cleanupStatus = S3ObjectCleanupStatus.AVAILABLE;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "deletion_reason", length = 1000)
    private String deletionReason;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status", length = 32)
    private S3ObjectVerificationStatus verificationStatus = S3ObjectVerificationStatus.UNKNOWN;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "actual_size_bytes")
    private Long actualSizeBytes;

    @Column(name = "verification_error", length = 1000)
    private String verificationError;
}
