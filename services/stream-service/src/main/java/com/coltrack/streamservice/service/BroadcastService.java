package com.coltrack.streamservice.service;

import com.coltrack.streamservice.client.CameraClient;
import com.coltrack.streamservice.config.BroadcastProperties;
import com.coltrack.streamservice.model.Broadcast;
import com.coltrack.streamservice.model.BroadcastVisibility;
import com.coltrack.streamservice.repository.BroadcastRepository;
import com.coltrack.streamservice.service.StreamManager;
import com.coltrack.identity.model.TenantMembership;
import com.coltrack.identity.security.JdbcTenantMembershipResolver;
import com.coltrack.identity.security.TenantContext;
import com.coltrack.identity.quota.TenantQuotaGuard;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class BroadcastService {
    private final BroadcastRepository repository;
    private final CameraClient cameraClient;
    private final StreamManager streamManager;
    private final PlaybackTokenService tokens;
    private final BroadcastProperties properties;
    private final JdbcTenantMembershipResolver membershipResolver;
    private final BroadcastQuotaLifecycle quotaLifecycle;

    @Autowired
    public BroadcastService(BroadcastRepository repository, CameraClient cameraClient,
                            StreamManager streamManager, PlaybackTokenService tokens,
                            BroadcastProperties properties, JdbcTenantMembershipResolver membershipResolver,
                            BroadcastQuotaLifecycle quotaLifecycle) {
        this.repository = repository;
        this.cameraClient = cameraClient;
        this.streamManager = streamManager;
        this.tokens = tokens;
        this.properties = properties;
        this.membershipResolver = membershipResolver;
        this.quotaLifecycle = quotaLifecycle;
    }

    public BroadcastService(BroadcastRepository repository, CameraClient cameraClient,
                            StreamManager streamManager, PlaybackTokenService tokens,
                            BroadcastProperties properties, JdbcTenantMembershipResolver membershipResolver) {
        this(repository, cameraClient, streamManager, tokens, properties, membershipResolver, null);
    }

    public BroadcastService(BroadcastRepository repository, CameraClient cameraClient,
                            StreamManager streamManager, PlaybackTokenService tokens,
                            BroadcastProperties properties) {
        this(repository, cameraClient, streamManager, tokens, properties, null, null);
    }

    public synchronized Broadcast create(UUID cameraId) {
        return create(cameraId, BroadcastVisibility.PRIVATE);
    }

    public synchronized Broadcast create(UUID cameraId, BroadcastVisibility visibility) {
        return repository.findByCamera(cameraId).orElseGet(() -> {
            cameraClient.connection(cameraId); // validates existence; credentials stay server-side
            try {
                return repository.create(UUID.randomUUID(), cameraId, UUID.randomUUID(), visibility, Instant.now());
            } catch (DuplicateKeyException race) {
                return repository.findByCamera(cameraId).orElseThrow(() -> race);
            }
        });
    }

    public synchronized Broadcast create(UUID cameraId, UUID tenantId, BroadcastVisibility visibility) {
        Optional<Broadcast> existingForTenant = repository.findByCamera(cameraId, tenantId);
        if (existingForTenant.isPresent()) return existingForTenant.get();
        // The camera_id unique constraint may find a legacy/unassigned or inconsistent row.
        // Never claim it for a tenant or leak which tenant owns it.
        if (repository.findByCamera(cameraId).isPresent()) throw new BroadcastNotFoundException(cameraId);
        try {
            cameraClient.connection(cameraId, tenantId); // signed workload context verifies camera ownership
            return repository.create(UUID.randomUUID(), cameraId, UUID.randomUUID(), visibility, tenantId, Instant.now());
        } catch (DuplicateKeyException race) {
            return repository.findByCamera(cameraId, tenantId).orElseThrow(() -> new BroadcastNotFoundException(cameraId));
        }
    }

    public Broadcast get(UUID id) {
        return repository.find(id).orElseThrow(() -> new BroadcastNotFoundException(id));
    }

    public Broadcast get(UUID id, UUID tenantId) {
        return repository.find(id, tenantId).orElseThrow(() -> new BroadcastNotFoundException(id));
    }

    public List<Broadcast> list() {
        return repository.findAll();
    }

    public List<Broadcast> list(UUID tenantId) {
        return repository.findAll(tenantId);
    }

    public Optional<Broadcast> findByCamera(UUID cameraId) {
        return repository.findByCamera(cameraId);
    }

    public Optional<Broadcast> findByCamera(UUID cameraId, UUID tenantId) {
        return repository.findByCamera(cameraId, tenantId);
    }

    public synchronized Broadcast setEnabled(UUID id, boolean enabled) {
        Broadcast current = get(id);
        if (current.enabled() == enabled) {
            if (enabled) acquireBroadcastLease(current);
            return get(id);
        }

        if (enabled) {
            Broadcast updated = repository.setEnabled(id, true, Instant.now());
            acquireBroadcastLease(updated);
            return get(id);
        }

        repository.setEnabled(id, false, Instant.now());
        streamManager.release(current.cameraId(), broadcastConsumerId(id));
        return get(id);
    }

    public synchronized Broadcast setEnabled(UUID id, UUID tenantId, boolean enabled) {
        if (tenantId != null && membershipResolver != null && quotaLifecycle == null)
            throw new TenantQuotaGuard.QuotaAuthorityUnavailableException(new IllegalStateException("Broadcast quota lifecycle is not configured"));
        if (quotaLifecycle != null) {
            BroadcastQuotaLifecycle.Transition transition = quotaLifecycle.setEnabled(id, tenantId, enabled);
            if (enabled) acquireBroadcastLease(transition.after());
            else if (transition.before().enabled()) streamManager.release(transition.before().cameraId(), tenantId, broadcastConsumerId(id));
            return transition.after();
        }
        Broadcast current = get(id, tenantId);
        if (current.enabled() == enabled) {
            if (enabled) acquireBroadcastLease(current);
            return get(id, tenantId);
        }
        if (enabled) {
            Broadcast updated = repository.setEnabled(id, tenantId, true, Instant.now());
            acquireBroadcastLease(updated);
            return get(id, tenantId);
        }
        repository.setEnabled(id, tenantId, false, Instant.now());
        streamManager.release(current.cameraId(), tenantId, broadcastConsumerId(id));
        return get(id, tenantId);
    }

    public synchronized Broadcast setVisibility(UUID id, BroadcastVisibility visibility) {
        get(id);
        return repository.setVisibility(id, visibility, Instant.now());
    }

    public synchronized Broadcast setVisibility(UUID id, UUID tenantId, BroadcastVisibility visibility) {
        get(id, tenantId);
        return repository.setVisibility(id, tenantId, visibility, Instant.now());
    }

    public synchronized void delete(UUID id) {
        Broadcast current = repository.find(id).orElse(null);
        if (current == null) return;
        repository.delete(id); // invalidate playback before stopping media
        streamManager.release(current.cameraId(), broadcastConsumerId(id));
    }

    public synchronized void delete(UUID id, UUID tenantId) {
        if (membershipResolver != null && quotaLifecycle == null)
            throw new TenantQuotaGuard.QuotaAuthorityUnavailableException(new IllegalStateException("Broadcast quota lifecycle is not configured"));
        if (quotaLifecycle != null) {
            Broadcast current = quotaLifecycle.delete(id, tenantId);
            streamManager.release(current.cameraId(), tenantId, broadcastConsumerId(id));
            return;
        }
        Broadcast current = repository.find(id, tenantId).orElse(null);
        if (current == null) throw new BroadcastNotFoundException(id);
        repository.delete(id, tenantId);
        streamManager.release(current.cameraId(), tenantId, broadcastConsumerId(id));
    }

    public TenantQuotaGuard.Usage usage(UUID tenantId) {
        if (quotaLifecycle == null) throw new TenantQuotaGuard.QuotaAuthorityUnavailableException(
                new IllegalStateException("Quota authority is not configured"));
        return quotaLifecycle.usage(tenantId);
    }

    public synchronized void deleteForCamera(UUID cameraId) {
        repository.findByCamera(cameraId).ifPresent(broadcast -> delete(broadcast.id()));
    }

    public synchronized void deleteForCamera(UUID cameraId, UUID tenantId) {
        repository.findByCamera(cameraId, tenantId).ifPresent(broadcast -> delete(broadcast.id(), tenantId));
    }

    public PlaybackInfo playbackInfo(UUID id) {
        Broadcast broadcast = get(id);
        String player = properties.getPlayerBaseUrl() + "/" + broadcast.publicId();
        return new PlaybackInfo(broadcast.publicId(), broadcast.visibility(), broadcast.enabled(),
                player, "<iframe src=\"" + player + "?embed=1\" title=\"Live broadcast\" referrerpolicy=\"no-referrer\" allow=\"autoplay; fullscreen; picture-in-picture\" allowfullscreen loading=\"lazy\"></iframe>");
    }

    public PlaybackInfo playbackInfo(UUID id, UUID tenantId) {
        return playbackInfo(get(id, tenantId));
    }

    private PlaybackInfo playbackInfo(Broadcast broadcast) {
        String player = properties.getPlayerBaseUrl() + "/" + broadcast.publicId();
        return new PlaybackInfo(broadcast.publicId(), broadcast.visibility(), broadcast.enabled(),
                player, "<iframe src=\"" + player + "?embed=1\" title=\"Live broadcast\" referrerpolicy=\"no-referrer\" allow=\"autoplay; fullscreen; picture-in-picture\" allowfullscreen loading=\"lazy\"></iframe>");
    }

    public PlaybackToken playbackToken(UUID publicId) {
        Broadcast broadcast = repository.findByPublicId(publicId).orElseThrow(() -> new BroadcastNotFoundException(publicId));
        if (membershipResolver != null && broadcast.tenantId() == null) throw new BroadcastNotFoundException(publicId);
        if (broadcast.visibility() != BroadcastVisibility.PUBLIC) throw new PrivateBroadcastException();
        if (!broadcast.enabled()) throw new BroadcastOfflineException();
        Instant expiresAt = Instant.now().plus(properties.getPlaybackTtl());
        String token = tokens.issue(broadcast.id(), broadcast.playbackVersion(), expiresAt, broadcast.tenantId(), null);
        String playlist = "/broadcast-play/" + publicId + "/index.m3u8?token=" + token;
        return new PlaybackToken(expiresAt, token, playlist);
    }

    public PlaybackToken playbackToken(UUID publicId, TenantContext viewer) {
        Broadcast broadcast = repository.findByPublicId(publicId).orElseThrow(() -> new BroadcastNotFoundException(publicId));
        if (!broadcast.enabled()) throw new BroadcastOfflineException();
        if (broadcast.visibility() == BroadcastVisibility.PUBLIC) return playbackToken(publicId);
        if (viewer == null || broadcast.tenantId() == null || !broadcast.tenantId().equals(viewer.tenantId())
                || membershipResolver == null
                || !membershipResolver.hasPermission(viewer.userId(), viewer.tenantId(), "playback.view")) {
            throw new PrivateBroadcastException();
        }
        Instant expiresAt = Instant.now().plus(properties.getPlaybackTtl());
        String token = tokens.issue(broadcast.id(), broadcast.playbackVersion(), expiresAt,
                broadcast.tenantId(), viewer.userId());
        return new PlaybackToken(expiresAt, token,
                "/broadcast-play/" + publicId + "/index.m3u8?token=" + token);
    }

    public Broadcast authorizePlayback(UUID publicId, String token, Instant now) {
        Broadcast broadcast = repository.findByPublicId(publicId).orElseThrow(() -> new BroadcastNotFoundException(publicId));
        var grant = tokens.verify(token, now).orElseThrow(PrivateBroadcastException::new);
        if (!broadcast.enabled() || !grant.broadcastId().equals(broadcast.id())
                || grant.playbackVersion() != broadcast.playbackVersion()) {
            throw new PrivateBroadcastException();
        }
        if (broadcast.visibility() == BroadcastVisibility.PUBLIC) {
            if ((membershipResolver != null && broadcast.tenantId() == null)
                    || (broadcast.tenantId() != null && !broadcast.tenantId().equals(grant.tenantId()))) {
                throw new PrivateBroadcastException();
            }
        } else if (broadcast.tenantId() == null || !broadcast.tenantId().equals(grant.tenantId())
                || grant.userId() == null || membershipResolver == null
                || !membershipResolver.hasPermission(grant.userId(), grant.tenantId(), "playback.view")) {
            throw new PrivateBroadcastException();
        }
        return broadcast;
    }

    private void acquireBroadcastLease(Broadcast broadcast) {
        if (broadcast.tenantId() == null) {
            streamManager.acquire(broadcast.cameraId(), broadcastConsumerId(broadcast.id()), "BROADCAST");
        } else {
            streamManager.acquire(broadcast.cameraId(), broadcast.tenantId(), broadcastConsumerId(broadcast.id()), "BROADCAST");
        }
    }

    private String broadcastConsumerId(UUID id) { return "broadcast:" + id; }

    public record PlaybackInfo(UUID publicId, BroadcastVisibility visibility, boolean enabled,
                               String playerUrl, String embedHtml) {}
    public record PlaybackToken(Instant expiresAt, String token, String playlistUrl) {}

    public static class BroadcastNotFoundException extends RuntimeException {
        public BroadcastNotFoundException(UUID id) { super("Broadcast not found: " + id); }
    }

    public static class BroadcastOfflineException extends RuntimeException {
        public BroadcastOfflineException() { super("Broadcast is disabled"); }
    }

    public static class PrivateBroadcastException extends RuntimeException {
        public PrivateBroadcastException() { super("Broadcast is private"); }
    }
}
