package com.coltrack.analyticsservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

@ConfigurationProperties(prefix = "analytics.input")
public record AnalyticsInputProperties(Path file) {
}
