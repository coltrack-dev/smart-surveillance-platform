package com.coltrack.recordingservice.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

@Data
@ConfigurationProperties(prefix = "recording.s3.management")
public class S3StoragePolicyProperties {

    private DataSize maximumSize = DataSize.ofGigabytes(500);
    private int cleanupTargetPercent = 90;
    private int retentionDays = 180;
    private int maxRecordingsPerRun = 100;
    private boolean deletionEnabled = false;
    private boolean deleteHybridEnabled = false;
    private boolean requireVerifiedBeforeDeletion = true;
    private Duration reconciliationGracePeriod = Duration.ofMinutes(5);

    @PostConstruct
    void validate() {
        if (maximumSize == null || maximumSize.toBytes() <= 0) {
            throw new IllegalStateException("recording.s3.management.maximum-size must be positive");
        }
        if (cleanupTargetPercent < 1 || cleanupTargetPercent > 100) {
            throw new IllegalStateException("recording.s3.management.cleanup-target-percent must be between 1 and 100");
        }
        if (retentionDays < 1) {
            throw new IllegalStateException("recording.s3.management.retention-days must be positive");
        }
        if (maxRecordingsPerRun < 1) {
            throw new IllegalStateException("recording.s3.management.max-recordings-per-run must be positive");
        }
        if (reconciliationGracePeriod == null || reconciliationGracePeriod.isNegative()) {
            throw new IllegalStateException(
                    "recording.s3.management.reconciliation-grace-period must not be negative"
            );
        }
    }
}
