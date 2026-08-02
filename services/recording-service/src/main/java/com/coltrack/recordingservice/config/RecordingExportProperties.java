package com.coltrack.recordingservice.config;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "recording.export")
public class RecordingExportProperties {

    private boolean enabled;

    private String endpoint;

    @NotBlank
    private String bucket;

    @NotBlank
    private String accessKey;

    @NotBlank
    private String secretKey;

    @NotBlank
    private String region = "us-east-1";

    private boolean deleteLocalAfterUpload;
}
