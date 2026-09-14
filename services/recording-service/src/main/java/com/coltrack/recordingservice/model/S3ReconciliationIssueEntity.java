package com.coltrack.recordingservice.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "s3_reconciliation_issues",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_s3_reconciliation_issue_key",
                columnNames = "s3_key"
        )
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class S3ReconciliationIssueEntity {

    @Id
    private UUID id;

    @Column(name = "s3_key", nullable = false, length = 1024)
    private String s3Key;

    @Enumerated(EnumType.STRING)
    @Column(name = "problem_type", nullable = false, length = 32)
    private S3ReconciliationProblemType problemType;

    @Column(name = "actual_size_bytes")
    private Long actualSizeBytes;

    @Column(name = "details", length = 1000)
    private String details;

    @Column(name = "first_detected_at", nullable = false)
    private Instant firstDetectedAt;

    @Column(name = "last_checked_at", nullable = false)
    private Instant lastCheckedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolution", length = 32)
    private String resolution;
}
