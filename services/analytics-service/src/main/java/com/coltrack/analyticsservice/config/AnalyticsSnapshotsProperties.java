package com.coltrack.analyticsservice.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@Data
@ConfigurationProperties(prefix = "analytics.snapshots.s3")
public class AnalyticsSnapshotsProperties {

    private String endpoint;

    private String region = "us-central-1";

    private String bucket;

    private String accessKey;

    private String secretKey;

    private String prefix = "analytics/snapshots";
}
