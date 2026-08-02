package com.coltrack.recordingservice.service;

import com.coltrack.recordingservice.model.RecordingEntity;
import com.coltrack.recordingservice.model.RecordingStatus;
import com.coltrack.recordingservice.repository.RecordingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RecordingMetadataService {

    private final RecordingRepository repository;


    public RecordingEntity create(
            UUID cameraId,
            String filePath
    ) {

        RecordingEntity entity =
                RecordingEntity.builder()
                        .id(UUID.randomUUID())
                        .cameraId(cameraId)
                        .filePath(filePath)
                        .startedAt(Instant.now())
                        .status(RecordingStatus.STARTING)
                        .build();

        return repository.save(entity);
    }


    public void complete(
            RecordingEntity entity,
            long sizeBytes,
            int exitCode
    ) {

        Instant now = Instant.now();

        entity.setFinishedAt(now);

        entity.setDurationSeconds(
                now.getEpochSecond()
                        -
                        entity.getStartedAt()
                                .getEpochSecond()
        );

        entity.setSizeBytes(sizeBytes);

        entity.setExitCode(exitCode);

        entity.setStatus(
                RecordingStatus.STOPPED
        );

        repository.save(entity);
    }

    public void failed(
            RecordingEntity entity,
            String reason
    ) {

        entity.setFinishedAt(
                Instant.now()
        );

        entity.setStatus(
                RecordingStatus.FAILED
        );

        entity.setReason(reason);

        repository.save(entity);
    }
}
