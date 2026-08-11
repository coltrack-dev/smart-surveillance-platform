package com.coltrack.analyticsservice.service;

import com.coltrack.analyticsservice.entity.AnalyticsEventEntity;
import com.coltrack.analyticsservice.mapper.AnalyticsEventMapper;
import com.coltrack.analyticsservice.repository.AnalyticsEventRepository;
import com.coltrack.events.analytics.AnalyticsEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalyticsEventServiceTest {

    @Mock
    private AnalyticsEventRepository repository;

    @Mock
    private AnalyticsEventMapper mapper;

    @InjectMocks
    private AnalyticsEventService service;

    @Test
    void shouldSaveNewEvent() {
        AnalyticsEvent event = event();
        AnalyticsEventEntity entity = new AnalyticsEventEntity();
        when(repository.existsById(event.eventId())).thenReturn(false);
        when(mapper.toEntity(event)).thenReturn(entity);

        assertThat(service.saveIfAbsent(event)).isTrue();

        verify(repository).saveAndFlush(entity);
    }

    @Test
    void shouldSkipExistingEvent() {
        AnalyticsEvent event = event();
        when(repository.existsById(event.eventId())).thenReturn(true);

        assertThat(service.saveIfAbsent(event)).isFalse();

        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void shouldSkipEventInsertedConcurrently() {
        AnalyticsEvent event = event();
        AnalyticsEventEntity entity = new AnalyticsEventEntity();
        when(repository.existsById(event.eventId())).thenReturn(false, true);
        when(mapper.toEntity(event)).thenReturn(entity);
        when(repository.saveAndFlush(entity))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThat(service.saveIfAbsent(event)).isFalse();
    }

    private AnalyticsEvent event() {
        return new AnalyticsEvent(
                UUID.randomUUID(), 1, "LINE_CROSSED", "camera-1",
                10L, "PERSON", null, 25L, null,
                OffsetDateTime.now(), Map.of("direction", "UP")
        );
    }
}
