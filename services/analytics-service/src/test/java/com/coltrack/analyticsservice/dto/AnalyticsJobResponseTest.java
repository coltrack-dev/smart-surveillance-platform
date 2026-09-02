package com.coltrack.analyticsservice.dto;

import com.coltrack.analyticsservice.entity.AnalyticsJobEntity;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AnalyticsJobResponseTest {

    @Test
    void hidesStandardRtspPassword() {
        AnalyticsJobResponse response = AnalyticsJobResponse.fromEntity(
                job("rtsp://operator:secret@nvr.lan:554/live")
        );

        assertThat(response.sourceUrl())
                .isEqualTo("rtsp://operator:***@nvr.lan:554/live")
                .doesNotContain("secret");
    }

    @Test
    void hidesXmRtspPassword() {
        AnalyticsJobResponse response = AnalyticsJobResponse.fromEntity(
                job("rtsp://nvr.lan:554/user=operator_password=secret_channel=8_stream=1.sdp")
        );

        assertThat(response.sourceUrl())
                .contains("_password=***_channel=8")
                .doesNotContain("secret");
    }

    private AnalyticsJobEntity job(String sourceUrl) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return AnalyticsJobEntity.builder()
                .jobId(UUID.randomUUID())
                .cameraId(UUID.randomUUID())
                .jobType("REALTIME")
                .status("RUNNING")
                .sourceUrl(sourceUrl)
                .profile(Map.of())
                .details(new HashMap<>())
                .createdAt(now)
                .updatedAt(now)
                .build();
    }
}
