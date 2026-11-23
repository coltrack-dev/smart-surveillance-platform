package com.coltrack.streamservice.service;

import com.coltrack.streamservice.client.CameraClient;
import com.coltrack.streamservice.config.BroadcastProperties;
import com.coltrack.streamservice.model.Broadcast;
import com.coltrack.streamservice.model.BroadcastVisibility;
import com.coltrack.streamservice.repository.BroadcastRepository;
import com.coltrack.streamservice.service.StreamManager;
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

    public BroadcastService(BroadcastRepository repository, CameraClient cameraClient,
                            StreamManager streamManager, PlaybackTokenService tokens,
                            BroadcastProperties properties) {
        this.repository = repository;
        this.cameraClient = cameraClient;
        this.streamManager = streamManager;
        this.tokens = tokens;
        this.properties = properties;
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

    public Broadcast get(UUID id) {
        return repository.find(id).orElseThrow(() -> new BroadcastNotFoundException(id));
    }

    public List<Broadcast> list() {
        return repository.findAll();
    }

    public Optional<Broadcast> findByCamera(UUID cameraId) {
        return repository.findByCamera(cameraId);
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

    public synchronized Broadcast setVisibility(UUID id, BroadcastVisibility visibility) {
        get(id);
        return repository.setVisibility(id, visibility, Instant.now());
    }

    public synchronized void delete(UUID id) {
        Broadcast current = repository.find(id).orElse(null);
        if (current == null) return;
        repository.delete(id); // invalidate playback before stopping media
        streamManager.release(current.cameraId(), broadcastConsumerId(id));
    }

    public synchronized void deleteForCamera(UUID cameraId) {
        repository.findByCamera(cameraId).ifPresent(broadcast -> delete(broadcast.id()));
    }

    public PlaybackInfo playbackInfo(UUID id) {
        Broadcast broadcast = get(id);
        String player = properties.getPlayerBaseUrl() + "/" + broadcast.publicId();
        return new PlaybackInfo(broadcast.publicId(), broadcast.visibility(), broadcast.enabled(),
                player, "<iframe src=\"" + player + "?embed=1\" title=\"Live broadcast\" referrerpolicy=\"no-referrer\" allow=\"autoplay; fullscreen; picture-in-picture\" allowfullscreen loading=\"lazy\"></iframe>");
    }

    public PlaybackToken playbackToken(UUID publicId) {
        Broadcast broadcast = repository.findByPublicId(publicId).orElseThrow(() -> new BroadcastNotFoundException(publicId));
        if (broadcast.visibility() != BroadcastVisibility.PUBLIC) throw new PrivateBroadcastException();
        if (!broadcast.enabled()) throw new BroadcastOfflineException();
        Instant expiresAt = Instant.now().plus(properties.getPlaybackTtl());
        String token = tokens.issue(broadcast.id(), broadcast.playbackVersion(), expiresAt);
        String playlist = "/broadcast-play/" + publicId + "/index.m3u8?token=" + token;
        return new PlaybackToken(expiresAt, token, playlist);
    }

    public Broadcast authorizePlayback(UUID publicId, String token, Instant now) {
        Broadcast broadcast = repository.findByPublicId(publicId).orElseThrow(() -> new BroadcastNotFoundException(publicId));
        if (broadcast.visibility() != BroadcastVisibility.PUBLIC || !broadcast.enabled()
                || !tokens.isValid(token, broadcast.id(), broadcast.playbackVersion(), now)) {
            throw new PrivateBroadcastException();
        }
        return broadcast;
    }

    private void acquireBroadcastLease(Broadcast broadcast) {
        streamManager.acquire(broadcast.cameraId(), broadcastConsumerId(broadcast.id()), "BROADCAST");
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
