package com.coltrack.streamservice.service;

import com.coltrack.streamservice.repository.ViewerSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/** Bounded, concurrent-safe expiry collection. Expiry stops counting before physical deletion. */
@Component
public class ViewerSessionCleanup {
    private static final Logger log = LoggerFactory.getLogger(ViewerSessionCleanup.class);
    private static final int BATCH_SIZE = 250;
    private final ViewerSessionRepository sessions;

    public ViewerSessionCleanup(ViewerSessionRepository sessions) {
        this.sessions = sessions;
    }

    @Scheduled(fixedDelayString = "${BROADCAST_VIEWER_SESSION_CLEANUP_INTERVAL:60000}")
    @Transactional
    public void deleteExpiredBatch() {
        int removed = sessions.deleteExpiredBatch(Instant.now(), BATCH_SIZE);
        if (removed > 0) log.debug("Removed {} expired/revoked viewer sessions", removed);
    }
}
