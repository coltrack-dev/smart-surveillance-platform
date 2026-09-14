package com.coltrack.recordingservice.service;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
public class DistributedLockService {
    public static final long S3_CLEANUP = 0x5333434cL;
    public static final long LOCAL_CLEANUP = 0x4c434c4eL;
    public static final long S3_RECONCILIATION = S3_CLEANUP;

    private final JdbcTemplate jdbcTemplate;

    public <T> T execute(long key, String operation, Supplier<T> action) {
        return execute(key, operation, action, false);
    }

    public <T> T executeBlocking(long key, String operation, Supplier<T> action) {
        return execute(key, operation, action, true);
    }

    private <T> T execute(long key, String operation, Supplier<T> action, boolean wait) {
        return jdbcTemplate.execute((ConnectionCallback<T>) connection -> {
            boolean acquired;
            String sql = wait ? "select pg_advisory_lock(?)" : "select pg_try_advisory_lock(?)";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setLong(1, key);
                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    acquired = wait || result.getBoolean(1);
                }
            }
            if (!acquired) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, operation + " is already running");
            }
            try {
                return action.get();
            } finally {
                try (PreparedStatement statement = connection.prepareStatement("select pg_advisory_unlock(?)")) {
                    statement.setLong(1, key);
                    statement.execute();
                }
            }
        });
    }
}
