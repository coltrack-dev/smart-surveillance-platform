package com.coltrack.recordingservice.service;

import com.coltrack.recordingservice.model.RecordingEntity;
import com.coltrack.recordingservice.model.RecordingSession;
import com.coltrack.recordingservice.model.RecordingStatus;
import com.coltrack.recordingservice.repository.RecordingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RecordingMetadataService {

    private final RecordingRepository repository;


    @Transactional
    public RecordingEntity create(
            UUID recordingId,
            UUID cameraId,
            String filePath,
            Instant startedAt
    ) {

        RecordingEntity entity =
                RecordingEntity.builder()
                        .id(recordingId)
                        .cameraId(cameraId)
                        .filePath(filePath)
                        .startedAt(startedAt)
                        .status(RecordingStatus.STARTING)
                        .build();

        return repository.saveAndFlush(
                entity
        );
    }

    @Transactional(readOnly = true)
    public Optional<RecordingEntity> find(UUID recordingId) {
        return repository.findById(recordingId);
    }

    @Transactional(readOnly = true)
    public Collection<RecordingEntity> findIncomplete() {
        return repository.findByStatusIn(
                java.util.List.of(
                        RecordingStatus.STARTING,
                        RecordingStatus.RECORDING,
                        RecordingStatus.STOPPING
                )
        );
    }

    @Transactional
    public void complete(
            RecordingEntity metadata,
            RecordingSession session
    ) {

        metadata.setFinishedAt(
                session.getFinishedAt()
        );

        metadata.setDurationSeconds(
                session.getDurationSeconds()
        );

        metadata.setSizeBytes(
                session.getSizeBytes()
        );

        metadata.setSegmentsCount(
                session.getSegmentsCount()
        );

        metadata.setExitCode(
                session.getExitCode()
        );

        metadata.setWidth(
                session.getWidth()
        );

        metadata.setHeight(
                session.getHeight()
        );

        metadata.setFps(
                session.getFps()
        );

        metadata.setCodec(
                session.getCodec()
        );

        metadata.setStatus(
                session.getStatus()
        );

        metadata.setReason(
                session.getLastError()
        );

        repository.saveAndFlush(
                metadata
        );
    }

    @Transactional
    public void markRecording(RecordingEntity entity) {
        entity.setStatus(RecordingStatus.RECORDING);
        repository.saveAndFlush(entity);
    }

    @Transactional
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

        repository.saveAndFlush(entity);
    }
}
