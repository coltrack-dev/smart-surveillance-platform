package com.coltrack.analyticsservice.service;

import com.coltrack.analyticsservice.entity.AnalyticsEventEntity;
import com.coltrack.analyticsservice.mapper.AnalyticsEventMapper;
import com.coltrack.analyticsservice.repository.AnalyticsEventRepository;
import com.coltrack.events.analytics.AnalyticsEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
@RequiredArgsConstructor
@Slf4j
public class AnalyticsEventService {

    private final AnalyticsEventRepository repository;
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
}
