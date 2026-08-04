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
}
