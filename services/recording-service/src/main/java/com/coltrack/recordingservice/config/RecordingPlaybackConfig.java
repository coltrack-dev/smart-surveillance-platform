package com.coltrack.recordingservice.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({
        RecordingPlaybackProperties.class
})
public class RecordingPlaybackConfig {
}
