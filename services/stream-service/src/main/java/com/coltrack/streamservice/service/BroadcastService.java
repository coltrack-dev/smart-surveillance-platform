package com.coltrack.streamservice.service;

import com.coltrack.streamservice.client.CameraClient;
import com.coltrack.streamservice.config.BroadcastProperties;
import com.coltrack.streamservice.model.Broadcast;
import com.coltrack.streamservice.model.StreamSession;
import com.coltrack.streamservice.repository.BroadcastRepository;
import com.coltrack.streamservice.service.StreamManager;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
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
        return repository.findByCamera(cameraId).orElseGet(() -> {
            cameraClient.connection(cameraId); // validates existence; credentials stay server-side
            try {
                return repository.create(UUID.randomUUID(), cameraId, Instant.now());
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
            if (enabled) ensureRunning(current);
            return get(id);
        }

        if (enabled) {
            boolean alreadyRunning = isOwnedSessionRunning(current.cameraId());
            if (!alreadyRunning) streamManager.start(current.cameraId());
            return repository.setState(id, true, current.ownsStream() || !alreadyRunning, Instant.now());
        }

        repository.setState(id, false, current.ownsStream(), Instant.now());
        if (current.ownsStream()) streamManager.stop(current.cameraId());
        return get(id);
    }

    public synchronized void delete(UUID id) {
        Broadcast current = repository.find(id).orElse(null);
        if (current == null) return;
        repository.delete(id); // invalidate playback before stopping media
        if (current.enabled() && current.ownsStream()) streamManager.stop(current.cameraId());
    }

    public PlaybackInfo playback(UUID id) {
        Broadcast broadcast = get(id);
        if (!broadcast.enabled()) throw new BroadcastOfflineException();
        Instant expiresAt = Instant.now().plus(properties.getPlaybackTtl());
        String token = tokens.issue(id, broadcast.playbackVersion(), expiresAt);
        String playlist = "/broadcast-play/" + id + "/index.m3u8?token=" + token;
        String player = properties.getPlayerBaseUrl() + "/" + id + "?token=" + token;
        return new PlaybackInfo(id, expiresAt, playlist, player,
                "<iframe src=\"" + player + "&amp;embed=1\" title=\"Live broadcast\" allow=\"autoplay; fullscreen; picture-in-picture\" allowfullscreen loading=\"lazy\"></iframe>");
    }

    public void restoreEnabledBroadcasts() {
        repository.findAll().stream().filter(Broadcast::enabled).forEach(broadcast -> {
            try {
                ensureRunning(broadcast);
            } catch (RuntimeException error) {
                log.warn("Could not restore enabled broadcast id={} camera={}", broadcast.id(), broadcast.cameraId(), error);
            }
        });
    }

    private void ensureRunning(Broadcast broadcast) {
        if (!isOwnedSessionRunning(broadcast.cameraId())) {
            streamManager.start(broadcast.cameraId());
            if (!broadcast.ownsStream()) repository.setState(broadcast.id(), true, true, Instant.now());
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void restoreOnStartup() { restoreEnabledBroadcasts(); }

    private boolean isOwnedSessionRunning(UUID cameraId) {
        StreamSession existing = streamManager.find(cameraId);
        return existing != null && existing.isWorkerRunning();
    }

    public record PlaybackInfo(UUID broadcastId, Instant expiresAt, String playlistUrl,
                               String playerUrl, String embedHtml) {}

    public static class BroadcastNotFoundException extends RuntimeException {
        public BroadcastNotFoundException(UUID id) { super("Broadcast not found: " + id); }
    }

    public static class BroadcastOfflineException extends RuntimeException {
        public BroadcastOfflineException() { super("Broadcast is disabled"); }
    }
}
