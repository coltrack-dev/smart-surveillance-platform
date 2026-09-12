package com.coltrack.recordingservice.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

@Getter
@Setter
@ConfigurationProperties(prefix = "recording.storage")
public class RecordingStoragePolicyProperties {

    private DataSize maximumLocalSize = DataSize.ofGigabytes(100);
    private int cleanupTargetPercent = 90;
    private int retentionDays = 30;
    private int minimumFreePercent = 15;
    private int emergencyFreePercent = 5;
    private int maxRecordingsPerRun = 100;
    private boolean deleteLocalOnlyEnabled = false;

    @PostConstruct
    void validate() {
        if (maximumLocalSize == null || maximumLocalSize.toBytes() <= 0) {
            throw new IllegalArgumentException(
                    "recording.storage.maximum-local-size must be positive"
            );
        }
        if (cleanupTargetPercent < 1 || cleanupTargetPercent > 100) {
            throw new IllegalArgumentException(
                    "recording.storage.cleanup-target-percent must be between 1 and 100"
            );
        }
        if (retentionDays < 1) {
            throw new IllegalArgumentException(
                    "recording.storage.retention-days must be positive"
            );
        }
        if (emergencyFreePercent < 0
                || minimumFreePercent > 100
                || emergencyFreePercent >= minimumFreePercent) {
            throw new IllegalArgumentException(
                    "Storage policy must satisfy 0 <= emergency-free-percent "
                            + "< minimum-free-percent <= 100"
            );
        }
        if (maxRecordingsPerRun < 1) {
            throw new IllegalArgumentException(
                    "recording.storage.max-recordings-per-run must be positive"
            );
        }
    }
}
