package com.coltrack.streamservice.security;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class BroadcastRateLimiter {
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public boolean allow(String key, int limit) {
        long minute = Instant.now().getEpochSecond() / 60;
        Window window = windows.compute(key, (ignored, previous) ->
                previous == null || previous.minute != minute
                        ? new Window(minute, new AtomicInteger()) : previous);
        if (windows.size() > 10_000) windows.entrySet().removeIf(entry -> entry.getValue().minute < minute - 1);
        return window.count.incrementAndGet() <= limit;
    }

    private record Window(long minute, AtomicInteger count) {}
}
