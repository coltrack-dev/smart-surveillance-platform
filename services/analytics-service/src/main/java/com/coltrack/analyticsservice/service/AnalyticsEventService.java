package com.coltrack.analyticsservice.service;

import com.coltrack.analyticsservice.dto.AnalyticsEventResponse;
import com.coltrack.analyticsservice.entity.AnalyticsEventEntity;
import com.coltrack.analyticsservice.mapper.AnalyticsEventMapper;
import com.coltrack.analyticsservice.repository.AnalyticsEventRepository;
import com.coltrack.analyticsservice.repository.AnalyticsJobRepository;
import com.coltrack.events.analytics.AnalyticsEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

import static com.coltrack.analyticsservice.repository.AnalyticsEventSpecifications.withFilters;

@Service
@RequiredArgsConstructor
@Slf4j
public class AnalyticsEventService {

    private final AnalyticsEventRepository repository;
    private final AnalyticsJobRepository jobRepository;
    private final AnalyticsEventMapper mapper;

    /**
     * Saves a previously unseen event.
     *
     * @return {@code true} when the row was inserted, or {@code false} when an
     * event with the same eventId already exists.
     */
    public boolean saveIfAbsent(AnalyticsEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        Objects.requireNonNull(event.eventId(), "eventId must not be null");

        if (repository.existsById(event.eventId())) {
            log.debug("Ignoring duplicate analytics event eventId={}", event.eventId());
            return false;
        }

        AnalyticsEventEntity entity = mapper.toEntity(event);

        try {
            // saveAndFlush makes the database primary-key check happen here.
            // It also protects against two consumers racing after existsById().
            repository.saveAndFlush(entity);
            return true;
        } catch (DataIntegrityViolationException exception) {
            if (repository.existsById(event.eventId())) {
                log.debug("Ignoring concurrently inserted analytics event eventId={}",
                        event.eventId());
                return false;
            }

            throw exception;
        }
    }

    public Page<AnalyticsEventResponse> findEvents(
            String cameraId,
            String eventType,
            String objectType,
            OffsetDateTime from,
            OffsetDateTime to,
            Pageable pageable
    ) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Parameter 'from' must not be after 'to'"
            );
        }

        return repository.findAll(
                withFilters(
                        cameraId,
                        eventType,
                        objectType,
                        from,
                        to
                ),
                pageable
        ).map(AnalyticsEventResponse::fromEntity);
    }

    public AnalyticsEventResponse findById(UUID eventId) {
        return repository.findById(eventId)
                .map(AnalyticsEventResponse::fromEntity)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Analytics event not found: " + eventId
                ));
    }

    public Page<AnalyticsEventResponse> findEventsForJob(
            UUID jobId,
            Pageable pageable
    ) {
        var job = jobRepository.findById(jobId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Analytics job not found: " + jobId
                ));

        if (!"RECORDING".equals(job.getJobType()) || job.getRecordingId() == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Analytics job is not a recording analysis: " + jobId
            );
        }

        return repository.findAllByRecordingId(job.getRecordingId(), pageable)
                .map(AnalyticsEventResponse::fromEntity);
    }
}
