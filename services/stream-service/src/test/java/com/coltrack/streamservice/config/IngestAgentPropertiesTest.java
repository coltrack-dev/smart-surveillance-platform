package com.coltrack.streamservice.config;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IngestAgentPropertiesTest {

    private static final UUID CAMERA_ID =
            UUID.fromString("29b88ec8-2c36-4879-a7d8-f8e1a4ee4443");

    @Test
    void buildsMediaMtxPublishAndPublicHlsUrls() {
        IngestAgentProperties properties = new IngestAgentProperties();
        properties.setPublishRtspBaseUrl("rtsp://mediamtx:8554/");
        properties.setInternalHlsBaseUrl("http://mediamtx:8888/");
        properties.setPublicHlsPrefix("/media-hls/");

        assertEquals(
                "rtsp://mediamtx:8554/" + CAMERA_ID,
                properties.publishUrl(CAMERA_ID)
        );
        assertEquals(
                "/media-hls/" + CAMERA_ID + "/index.m3u8",
                properties.publicHlsUrl(CAMERA_ID)
        );
        assertEquals("http://mediamtx:8888/", properties.getInternalHlsBaseUrl());
    }
}
