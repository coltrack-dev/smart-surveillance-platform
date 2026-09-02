package com.coltrack.analyticsservice.service;

import com.coltrack.analyticsservice.dto.AnalyticsJobResponse;
import com.coltrack.analyticsservice.dto.AnalyticsWorkerResponse;
import com.coltrack.analyticsservice.dto.RealtimeAnalyticsStartRequest;
import com.coltrack.analyticsservice.dto.RecordingAnalyticsStartRequest;
import com.coltrack.analyticsservice.entity.AnalyticsJobEntity;
import com.coltrack.analyticsservice.entity.AnalyticsWorkerEntity;
import com.coltrack.analyticsservice.repository.AnalyticsJobRepository;
import com.coltrack.analyticsservice.repository.AnalyticsWorkerRepository;
import com.coltrack.events.analytics.AnalyticsJob;
import com.coltrack.events.analytics.AnalyticsJobStatusEvent;
import com.coltrack.events.analytics.AnalyticsProfile;
import com.coltrack.events.analytics.AnalyticsLine;
import com.coltrack.events.analytics.NormalizedPoint;
import com.coltrack.events.analytics.AnalyticsSource;
import com.coltrack.events.analytics.AnalyticsWorkerHeartbeatEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class AnalyticsControlService {

    private static final Set<String> ACTIVE_STATUSES = Set.of(
            "REQUESTED", "RUNNING", "RETRYING", "STOP_REQUESTED"
    );
    private static final Set<String> FINISHED_STATUSES = Set.of(
            "COMPLETED", "STOPPED", "FAILED", "REJECTED"
    );
    private static final Set<String> KNOWN_STATUSES = Set.of(
            "REQUESTED", "RUNNING", "RETRYING", "STOP_REQUESTED",
            "COMPLETED", "STOPPED", "FAILED", "REJECTED"
    );
    private static final Map<String, Set<String>> ALLOWED_TRANSITIONS = Map.of(
            "REQUESTED", Set.of(
                    "RUNNING", "RETRYING", "STOP_REQUESTED",
                    "COMPLETED", "STOPPED", "FAILED", "REJECTED"
            ),
            "RUNNING", Set.of(
                    "RETRYING", "STOP_REQUESTED", "COMPLETED",
                    "STOPPED", "FAILED", "REJECTED"
            ),
            "RETRYING", Set.of(
                    "RUNNING", "STOP_REQUESTED", "COMPLETED",
                    "STOPPED", "FAILED", "REJECTED"
            ),
            "STOP_REQUESTED", Set.of("STOPPED", "FAILED")
    );

    private final AnalyticsJobRepository jobRepository;
    private final AnalyticsWorkerRepository workerRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${analytics.kafka.jobs-topic:analytics.jobs}")
    private String jobsTopic;

    @Value("${analytics.workers.online-timeout:45s}")
    private Duration workerOnlineTimeout;

    public AnalyticsJobResponse startRealtime(
            UUID cameraId,
            RealtimeAnalyticsStartRequest request
    ) {
        if (request == null || request.sourceUrl() == null || request.sourceUrl().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sourceUrl is required");
        }
        if (!request.sourceUrl().startsWith("rtsp://")
                && !request.sourceUrl().startsWith("rtsps://")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "sourceUrl must use rtsp:// or rtsps://"
            );
        }
        validateProfile(request);

        jobRepository
                .findFirstByCameraIdAndJobTypeAndStatusInOrderByCreatedAtDesc(
                        cameraId, "REALTIME", ACTIVE_STATUSES
                )
                .ifPresent(existing -> {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "Realtime analytics is already active: " + existing.getJobId()
                    );
                });

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        UUID jobId = UUID.randomUUID();
        String transport = defaultString(request.transport(), "tcp");
        AnalyticsProfile profile = new AnalyticsProfile(
                request.model(),
                defaultClasses(request.classes()),
                defaultDecimal(request.confidence(), "0.5"),
                defaultString(request.devicePreference(), "auto"),
                defaultDecimal(request.linePosition(), "0.5"),
                defaultLines(request.lines(), request.linePosition()),
                defaultDecimal(request.targetFps(), "10"),
                request.attributes() == null ? Map.of() : request.attributes()
        );
        AnalyticsJob job = new AnalyticsJob(
                jobId,
                1,
                "ANALYTICS_JOB",
                "REALTIME",
                "START",
                cameraId,
                null,
                new AnalyticsSource("RTSP", request.sourceUrl(), transport),
                profile,
                now
        );

        AnalyticsJobEntity entity = AnalyticsJobEntity.builder()
                .jobId(jobId)
                .cameraId(cameraId)
                .jobType("REALTIME")
                .status("REQUESTED")
                .sourceUrl(request.sourceUrl())
                .sourceTransport(transport)
                .profile(profileAsMap(profile))
                .details(new HashMap<>())
                .createdAt(now)
                .updatedAt(now)
                .build();
        // The worker may answer immediately, so the status consumer must see the job first.
        saveNewJob(entity, "Realtime analytics is already active for camera " + cameraId);

        try {
            send(cameraId, job);
        } catch (RuntimeException exception) {
            entity.setStatus("FAILED");
            entity.setFinishedAt(OffsetDateTime.now(ZoneOffset.UTC));
            entity.setUpdatedAt(entity.getFinishedAt());
            entity.setDetails(Map.of("errorCode", "KAFKA_PUBLISH_FAILED"));
            jobRepository.save(entity);
            throw exception;
        }
        return AnalyticsJobResponse.fromEntity(entity);
    }

    public AnalyticsJobResponse startRecording(
            UUID recordingId,
            RecordingAnalyticsStartRequest request
    ) {
        if (request == null || request.cameraId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "cameraId is required");
        }
        validateProfile(
                request.classes(),
                request.confidence(),
                request.linePosition(),
                request.lines(),
                request.targetFps()
        );
        jobRepository
                .findFirstByRecordingIdAndJobTypeAndStatusInOrderByCreatedAtDesc(
                        recordingId, "RECORDING", ACTIVE_STATUSES
                )
                .ifPresent(existing -> {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "Recording analytics is already active: " + existing.getJobId()
                    );
                });

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        UUID jobId = UUID.randomUUID();
        AnalyticsProfile profile = new AnalyticsProfile(
                request.model(),
                defaultClasses(request.classes()),
                defaultDecimal(request.confidence(), "0.5"),
                defaultString(request.devicePreference(), "auto"),
                defaultDecimal(request.linePosition(), "0.5"),
                defaultLines(request.lines(), request.linePosition()),
                defaultDecimal(request.targetFps(), "10"),
                request.attributes() == null ? Map.of() : request.attributes()
        );
        AnalyticsJob job = new AnalyticsJob(
                jobId,
                1,
                "ANALYTICS_JOB",
                "RECORDING",
                "START",
                request.cameraId(),
                recordingId,
                new AnalyticsSource("RECORDING_SERVICE", null, null),
                profile,
                now
        );
        AnalyticsJobEntity entity = AnalyticsJobEntity.builder()
                .jobId(jobId)
                .cameraId(request.cameraId())
                .recordingId(recordingId)
                .jobType("RECORDING")
                .status("REQUESTED")
                .profile(profileAsMap(profile))
                .details(new HashMap<>())
                .createdAt(now)
                .updatedAt(now)
                .build();

        // Commit the job before Kafka delivery to avoid a status-before-create race.
        saveNewJob(entity, "Recording analytics is already active for recording " + recordingId);
        try {
            send(request.cameraId(), job);
        } catch (RuntimeException exception) {
            entity.setStatus("FAILED");
            entity.setFinishedAt(OffsetDateTime.now(ZoneOffset.UTC));
            entity.setUpdatedAt(entity.getFinishedAt());
            entity.setDetails(Map.of("errorCode", "KAFKA_PUBLISH_FAILED"));
            jobRepository.save(entity);
            throw exception;
        }
        return AnalyticsJobResponse.fromEntity(entity);
    }

    @Transactional
    public AnalyticsJobResponse stopRealtime(UUID cameraId) {
        AnalyticsJobEntity entity = jobRepository
                .findActiveCameraJobForUpdate(
                        cameraId, "REALTIME", ACTIVE_STATUSES
                )
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "No active realtime analytics job for camera " + cameraId
                ));

        if ("STOP_REQUESTED".equals(entity.getStatus())) {
            return AnalyticsJobResponse.fromEntity(entity);
        }

        String previousStatus = entity.getStatus();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        entity.setStatus("STOP_REQUESTED");
        entity.setUpdatedAt(now);
        jobRepository.save(entity);

        AnalyticsJob stop = new AnalyticsJob(
                entity.getJobId(),
                1,
                "ANALYTICS_JOB",
                "REALTIME",
                "STOP",
                cameraId,
                null,
                null,
                null,
                now
        );
        try {
            send(cameraId, stop);
        } catch (RuntimeException exception) {
            entity.setStatus(previousStatus);
            entity.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
            jobRepository.save(entity);
            throw exception;
        }
        return AnalyticsJobResponse.fromEntity(entity);
    }

    @Transactional
    public AnalyticsJobResponse stopRecording(UUID recordingId) {
        AnalyticsJobEntity entity = jobRepository
                .findActiveRecordingJobForUpdate(
                        recordingId, "RECORDING", ACTIVE_STATUSES
                )
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "No active analytics job for recording " + recordingId
                ));

        if ("STOP_REQUESTED".equals(entity.getStatus())) {
            return AnalyticsJobResponse.fromEntity(entity);
        }

        String previousStatus = entity.getStatus();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        entity.setStatus("STOP_REQUESTED");
        entity.setUpdatedAt(now);
        jobRepository.saveAndFlush(entity);

        AnalyticsJob stop = new AnalyticsJob(
                entity.getJobId(),
                1,
                "ANALYTICS_JOB",
                "RECORDING",
                "STOP",
                entity.getCameraId(),
                recordingId,
                new AnalyticsSource("RECORDING_SERVICE", null, null),
                null,
                now
        );
        try {
            send(entity.getCameraId(), stop);
        } catch (RuntimeException exception) {
            entity.setStatus(previousStatus);
            entity.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
            jobRepository.save(entity);
            throw exception;
        }
        return AnalyticsJobResponse.fromEntity(entity);
    }

    public AnalyticsJobResponse findJob(UUID jobId) {
        return jobRepository.findById(jobId)
                .map(AnalyticsJobResponse::fromEntity)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Analytics job not found: " + jobId
                ));
    }

    public AnalyticsJobResponse findLatestRealtimeJob(UUID cameraId) {
        return jobRepository
                .findFirstByCameraIdAndJobTypeOrderByCreatedAtDesc(cameraId, "REALTIME")
                .map(AnalyticsJobResponse::fromEntity)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "No realtime analytics jobs for camera " + cameraId
                ));
    }

    public AnalyticsJobResponse findLatestRecordingJob(UUID recordingId) {
        return jobRepository
                .findFirstByRecordingIdAndJobTypeOrderByCreatedAtDesc(
                        recordingId, "RECORDING"
                )
                .map(AnalyticsJobResponse::fromEntity)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "No analytics jobs for recording " + recordingId
                ));
    }

    public Page<AnalyticsJobResponse> findJobs(Pageable pageable) {
        return jobRepository.findAll(pageable).map(AnalyticsJobResponse::fromEntity);
    }

    public List<AnalyticsWorkerResponse> findWorkers() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return workerRepository.findAll().stream()
                .map(worker -> AnalyticsWorkerResponse.fromEntity(
                        worker, now, workerOnlineTimeout
                ))
                .toList();
    }

    @Transactional
    public void applyStatus(AnalyticsJobStatusEvent event) {
        AnalyticsJobEntity entity = jobRepository.findByIdForUpdate(event.jobId())
                .orElseThrow(() -> new IllegalStateException(
                        "Status received before analytics job was committed: " + event.jobId()
                ));
        if (!KNOWN_STATUSES.contains(event.status())) {
            log.warn(
                    "Ignoring unknown analytics status jobId={} current={} incoming={}",
                    event.jobId(), entity.getStatus(), event.status()
            );
            return;
        }
        if (!matchesJobIdentity(entity, event)) {
            log.warn(
                    "Ignoring analytics status with mismatched identity jobId={} workerId={}",
                    event.jobId(), event.workerId()
            );
            return;
        }
        String currentStatus = entity.getStatus();
        String incomingStatus = event.status();
        if (FINISHED_STATUSES.contains(currentStatus)) {
            log.debug(
                    "Ignoring status for finished analytics job jobId={} current={} incoming={}",
                    event.jobId(), currentStatus, incomingStatus
            );
            return;
        }
        if (!currentStatus.equals(incomingStatus)
                && !ALLOWED_TRANSITIONS
                .getOrDefault(currentStatus, Set.of())
                .contains(incomingStatus)) {
            log.warn(
                    "Ignoring invalid analytics status transition jobId={} current={} incoming={}",
                    event.jobId(), currentStatus, incomingStatus
            );
            return;
        }
        OffsetDateTime occurredAt = event.occurredAt() == null
                ? OffsetDateTime.now(ZoneOffset.UTC)
                : event.occurredAt();
        entity.setStatus(incomingStatus);
        if (event.workerId() != null) {
            entity.setWorkerId(event.workerId());
        }
        entity.setUpdatedAt(occurredAt);
        entity.setDetails(event.details() == null ? new HashMap<>() : event.details());
        if ("RUNNING".equals(incomingStatus) && entity.getStartedAt() == null) {
            entity.setStartedAt(occurredAt);
        }
        if (FINISHED_STATUSES.contains(incomingStatus)) {
            entity.setFinishedAt(occurredAt);
        }
        jobRepository.save(entity);
    }

    private boolean matchesJobIdentity(
            AnalyticsJobEntity entity,
            AnalyticsJobStatusEvent event
    ) {
        return entity.getCameraId().equals(event.cameraId())
                && entity.getJobType().equals(event.jobType())
                && (entity.getRecordingId() == null
                ? event.recordingId() == null
                : entity.getRecordingId().equals(event.recordingId()));
    }

    private void saveNewJob(AnalyticsJobEntity entity, String conflictMessage) {
        try {
            jobRepository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    conflictMessage,
                    exception
            );
        }
    }

    public void applyHeartbeat(AnalyticsWorkerHeartbeatEvent event) {
        AnalyticsWorkerEntity entity = workerRepository.findById(event.workerId())
                .orElseGet(() -> AnalyticsWorkerEntity.builder()
                        .workerId(event.workerId())
                        .build());
        entity.setStatus(defaultString(event.status(), "ONLINE"));
        entity.setActiveJobs(event.activeJobs() == null ? 0 : event.activeJobs());
        entity.setMaxJobs(event.maxJobs() == null ? 1 : event.maxJobs());
        entity.setHost(event.host());
        entity.setPlatform(event.platform());
        entity.setCudaAvailable(Boolean.TRUE.equals(event.cudaAvailable()));
        entity.setCudaDeviceCount(
                event.cudaDeviceCount() == null ? 0 : event.cudaDeviceCount()
        );
        entity.setGpuName(event.gpuName());
        entity.setLastSeenAt(event.occurredAt() == null
                ? OffsetDateTime.now(ZoneOffset.UTC)
                : event.occurredAt());
        workerRepository.save(entity);
    }

    private void send(UUID cameraId, AnalyticsJob job) {
        try {
            kafkaTemplate.send(jobsTopic, cameraId.toString(), job).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing analytics job", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot publish analytics job", exception);
        }
    }

    private static Map<String, Object> profileAsMap(AnalyticsProfile profile) {
        Map<String, Object> result = new HashMap<>();
        result.put("model", profile.model());
        result.put("classes", profile.classes());
        result.put("confidence", profile.confidence());
        result.put("devicePreference", profile.devicePreference());
        result.put("linePosition", profile.linePosition());
        result.put("lines", profile.lines());
        result.put("targetFps", profile.targetFps());
        result.put("attributes", profile.attributes());
        return result;
    }

    private static void validateProfile(RealtimeAnalyticsStartRequest request) {
        validateProfile(
                request.classes(),
                request.confidence(),
                request.linePosition(),
                request.lines(),
                request.targetFps()
        );
    }

    private static void validateProfile(
            List<Integer> classes,
            BigDecimal confidence,
            BigDecimal linePosition,
            List<AnalyticsLine> lines,
            BigDecimal targetFps
    ) {
        if (confidence != null
                && (confidence.signum() < 0
                || confidence.compareTo(BigDecimal.ONE) > 0)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "confidence must be between 0 and 1"
            );
        }
        if (linePosition != null
                && (linePosition.signum() <= 0
                || linePosition.compareTo(BigDecimal.ONE) >= 0)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "linePosition must be between 0 and 1"
            );
        }
        if (targetFps != null && targetFps.signum() <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "targetFps must be positive"
            );
        }
        if (lines != null) {
            lines.forEach(AnalyticsControlService::validateLine);
        }
        if (classes != null
                && classes.stream().anyMatch(value -> value == null || value < 0)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "classes must contain non-negative ids"
            );
        }
    }

    private static List<AnalyticsLine> defaultLines(
            List<AnalyticsLine> lines,
            BigDecimal legacyPosition
    ) {
        if (lines != null && !lines.isEmpty()) {
            return List.copyOf(lines);
        }
        BigDecimal position = defaultDecimal(legacyPosition, "0.5");
        return List.of(new AnalyticsLine(
                "main-line",
                new NormalizedPoint(BigDecimal.ZERO, position),
                new NormalizedPoint(BigDecimal.ONE, position),
                "BOTTOM_CENTER",
                List.of(),
                Map.of("A_TO_B", "DOWN", "B_TO_A", "UP"),
                List.of(),
                new BigDecimal("2"),
                new BigDecimal("0.02"),
                3
        ));
    }

    private static void validateLine(AnalyticsLine line) {
        if (line == null || line.start() == null || line.end() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "each line requires start and end");
        }
        validatePoint(line.start());
        validatePoint(line.end());
        if (line.start().equals(line.end())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "line start and end must be different");
        }
        String anchor = defaultString(line.anchor(), "BOTTOM_CENTER").toUpperCase();
        if (!Set.of("BOTTOM_CENTER", "CENTER").contains(anchor)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "line anchor must be BOTTOM_CENTER or CENTER");
        }
        if (line.allowedDirections() != null && line.allowedDirections().stream()
                .map(String::toUpperCase)
                .anyMatch(value -> !Set.of("A_TO_B", "B_TO_A").contains(value))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "allowedDirections must contain A_TO_B or B_TO_A");
        }
        if (line.hysteresis() != null
                && (line.hysteresis().signum() < 0
                || line.hysteresis().compareTo(new BigDecimal("0.5")) >= 0)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "line hysteresis must be between 0 and 0.5");
        }
        if (line.cooldownSeconds() != null && line.cooldownSeconds().signum() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "line cooldownSeconds must not be negative");
        }
        if (line.minimumTrackAgeFrames() != null && line.minimumTrackAgeFrames() < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "line minimumTrackAgeFrames must be positive");
        }
    }

    private static void validatePoint(NormalizedPoint point) {
        if (point.x() == null || point.y() == null
                || point.x().signum() < 0 || point.x().compareTo(BigDecimal.ONE) > 0
                || point.y().signum() < 0 || point.y().compareTo(BigDecimal.ONE) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "line coordinates must be between 0 and 1");
        }
    }

    private static List<Integer> defaultClasses(List<Integer> classes) {
        return classes == null || classes.isEmpty() ? List.of(0) : List.copyOf(classes);
    }

    private static BigDecimal defaultDecimal(BigDecimal value, String defaultValue) {
        return value == null ? new BigDecimal(defaultValue) : value;
    }

    private static String defaultString(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
