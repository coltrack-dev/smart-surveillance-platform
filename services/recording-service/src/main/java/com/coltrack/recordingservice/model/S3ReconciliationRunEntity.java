package com.coltrack.recordingservice.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "s3_reconciliation_runs")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class S3ReconciliationRunEntity {

    @Id
    private UUID id;

    @Column(nullable = false, length = 32)
    private String status;

    private long databaseObjects;
    private long listedObjects;
    private long verifiedObjects;
    private long missingObjects;
    private long sizeMismatchObjects;
    private long verificationErrors;
    private long orphanObjects;
    private long deleteIncompleteObjects;
    private long catalogedBytes;
    private long actualBytes;

    @Column(nullable = false)
    private Instant checkedAt;
}
