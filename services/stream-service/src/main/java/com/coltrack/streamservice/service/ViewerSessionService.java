package com.coltrack.streamservice.service;

import com.coltrack.identity.model.TenantMembership;
import com.coltrack.identity.quota.TenantQuotaGuard;
import com.coltrack.identity.security.AuthenticatedPrincipal;
import com.coltrack.identity.security.JdbcTenantMembershipResolver;
import com.coltrack.streamservice.config.BroadcastProperties;
import com.coltrack.streamservice.model.Broadcast;
import com.coltrack.streamservice.model.BroadcastVisibility;
import com.coltrack.streamservice.repository.BroadcastRepository;
import com.coltrack.streamservice.repository.ViewerSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/** Persisted, tenant-scoped logical playback leases. Admission and renewal share the quota transaction lock. */
@Service
public class ViewerSessionService {
    private final BroadcastRepository broadcasts;
    private final ViewerSessionRepository sessions;
    private final TenantQuotaGuard quotas;
    private final JdbcTenantMembershipResolver memberships;
    private final PlaybackTokenService tokens;
    private final BroadcastProperties properties;

    public ViewerSessionService(BroadcastRepository broadcasts, ViewerSessionRepository sessions,
                                TenantQuotaGuard quotas, JdbcTenantMembershipResolver memberships,
                                PlaybackTokenService tokens, BroadcastProperties properties) {
        this.broadcasts = broadcasts;
        this.sessions = sessions;
        this.quotas = quotas;
        this.memberships = memberships;
        this.tokens = tokens;
        this.properties = properties;
    }

    @Transactional
    public Admission admitOrRenew(UUID publicId, String credential, AuthenticatedPrincipal principal,
                                  UUID selectedTenant, Instant now) {
        byte[] hash = credentialHash(credential);
        Broadcast initial = broadcasts.findByPublicId(publicId)
                .orElseThrow(() -> new BroadcastService.BroadcastNotFoundException(publicId));
        UUID tenantId = initial.tenantId();
        if (tenantId == null) throw new BroadcastService.BroadcastNotFoundException(publicId);

        // Lock the authoritative tenant before re-reading desired state or counting active sessions.
        quotas.lock(tenantId);
        Broadcast broadcast = broadcasts.findByPublicId(publicId)
                .orElseThrow(() -> new BroadcastService.BroadcastNotFoundException(publicId));
        if (!tenantId.equals(broadcast.tenantId())) throw new BroadcastService.BroadcastNotFoundException(publicId);
        if (!broadcast.enabled()) throw new BroadcastService.BroadcastOfflineException();

        UUID userId = null;
        if (broadcast.visibility() == BroadcastVisibility.PRIVATE) {
            if (principal == null || !tenantId.equals(selectedTenant))
                throw new BroadcastService.PrivateBroadcastException();
            TenantMembership membership = memberships.findMembership(principal, tenantId)
                    .filter(value -> memberships.hasPermission(principal, tenantId, "playback.view"))
                    .orElseThrow(BroadcastService.PrivateBroadcastException::new);
            userId = membership.userId();
        }

        Optional<ViewerSessionRepository.ViewerSession> existing = sessions.findByCredentialHash(hash);
        UUID sessionId;
        if (existing.isPresent()) {
            ViewerSessionRepository.ViewerSession session = existing.get();
            if (!tenantId.equals(session.tenantId()) || !broadcast.id().equals(session.broadcastId())
                    || !java.util.Objects.equals(userId, session.userId()) || session.revoked()) {
                throw new SessionCredentialConflictException();
            }
            if (session.expiredAt(now)) {
                quotas.requireCapacity(tenantId, TenantQuotaGuard.Resource.VIEWER);
                sessionId = UUID.randomUUID(); // invalidate every grant tied to the expired session id
                sessions.renew(session.sessionId(), sessionId, now, expiry(now));
            } else {
                quotas.validateViewerAuthority(tenantId);
                sessionId = session.sessionId();
                sessions.renew(sessionId, now, expiry(now));
            }
        } else {
            quotas.reserve(tenantId, TenantQuotaGuard.Resource.VIEWER);
            sessionId = UUID.randomUUID();
            sessions.create(sessionId, tenantId, broadcast.id(), userId, hash, now, expiry(now));
        }

        Instant grantExpiry = now.plus(properties.getPlaybackTtl());
        String grant = tokens.issue(broadcast.id(), broadcast.playbackVersion(), grantExpiry,
                tenantId, userId, sessionId);
        return new Admission(sessionId, grantExpiry, grant,
                "/broadcast-play/" + publicId + "/index.m3u8?token=" + grant);
    }

    public boolean isActive(UUID sessionId, UUID tenantId, UUID broadcastId, UUID userId, Instant now) {
        return sessionId != null && sessions.isActive(sessionId, tenantId, broadcastId, userId, now);
    }

    @Transactional
    public void release(UUID publicId, String credential, Instant now) {
        byte[] hash = credentialHash(credential);
        Broadcast broadcast = broadcasts.findByPublicId(publicId)
                .orElseThrow(() -> new BroadcastService.BroadcastNotFoundException(publicId));
        if (broadcast.tenantId() == null) return;
        quotas.lock(broadcast.tenantId());
        sessions.findByCredentialHash(hash).ifPresent(session -> {
            if (broadcast.tenantId().equals(session.tenantId()) && broadcast.id().equals(session.broadcastId()))
                sessions.revoke(session.sessionId(), now);
        });
    }

    @Transactional(readOnly = true)
    public TenantQuotaGuard.Usage usage(UUID tenantId) {
        return quotas.viewerUsage(tenantId);
    }

    private Instant expiry(Instant now) { return now.plus(properties.getViewerSessionTtl()); }

    private static byte[] credentialHash(String credential) {
        if (credential == null || credential.length() > 64) throw new InvalidSessionCredentialException();
        try {
            byte[] raw = Base64.getUrlDecoder().decode(credential);
            String canonical = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
            if (raw.length != 32 || !canonical.equals(credential)) throw new InvalidSessionCredentialException();
            return MessageDigest.getInstance("SHA-256").digest(raw);
        } catch (IllegalArgumentException error) {
            throw new InvalidSessionCredentialException();
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    public record Admission(UUID sessionId, Instant expiresAt, String token, String playlistUrl) {}

    public static class InvalidSessionCredentialException extends RuntimeException {}
    public static class SessionCredentialConflictException extends RuntimeException {}
}
