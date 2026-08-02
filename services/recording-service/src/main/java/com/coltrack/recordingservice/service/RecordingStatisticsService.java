package com.coltrack.recordingservice.service;

import com.coltrack.recordingservice.model.RecordingSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
@Slf4j
public class RecordingStatisticsService {

    public void collect(RecordingSession session, int exitCode) {

        Path dir = Paths.get(session.getFilePath());

        session.setExitCode(exitCode);

        session.setFinishedAt(Instant.now());

        if (session.getStartedAt() != null) {
            session.setDurationSeconds(
                    Duration.between(
                            session.getStartedAt(),
                            session.getFinishedAt()
                    ).toSeconds()
            );
        }

        try (Stream<Path> stream = Files.list(dir)) {

            List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .toList();

            session.setSegmentsCount(files.size());

            long total =
                    files.stream()
                            .mapToLong(file -> {
                                try {
                                    return Files.size(file);
                                } catch (IOException e) {
                                    return 0;
                                }
                            })
                            .sum();

            session.setSizeBytes(total);

        } catch (IOException e) {

            log.warn("Unable to collect recording statistics", e);
        }
    }
}
