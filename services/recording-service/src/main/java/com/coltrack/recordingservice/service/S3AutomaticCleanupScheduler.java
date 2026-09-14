package com.coltrack.recordingservice.service;

import com.coltrack.recordingservice.config.S3StoragePolicyProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Instant;

@Slf4j
@Component
@RequiredArgsConstructor
public class S3AutomaticCleanupScheduler {
    private final S3StoragePolicyProperties policy;
    private final S3StorageManagementService managementService;
    private final S3ReconciliationService reconciliationService;

    @Scheduled(fixedDelayString = "${recording.s3.management.automatic-cleanup-delay:1h}")
    public void cleanup() {
        if (!policy.isAutomaticCleanupEnabled() || !policy.isDeletionEnabled()) {
            return;
        }
        try {
            var reconciliation = reconciliationService.getLatest();
            Instant oldestAccepted = Instant.now().minus(policy.getAutomaticCleanupDelay().multipliedBy(2));
            if (!"COMPLETED".equals(reconciliation.status())
                    || reconciliation.checkedAt() == null
                    || reconciliation.checkedAt().isBefore(oldestAccepted)
                    || !reconciliation.problems().isEmpty()) {
                log.warn("Automatic S3 cleanup skipped: a recent problem-free reconciliation is required");
                return;
            }
            if (managementService.preview().cleanupRequired()) {
                managementService.runCleanup("AUTOMATIC");
            }
        } catch (RuntimeException exception) {
            log.error("Automatic S3 cleanup failed", exception);
        }
    }
}
