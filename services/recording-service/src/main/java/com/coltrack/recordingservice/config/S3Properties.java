package com.coltrack.recordingservice.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@Data
@ConfigurationProperties(prefix = "recording.s3")
public class S3Properties {

    /**
     * Enable S3 upload.
     */
    private boolean enabled = false;

    /**
     * Endpoint.
     * Example:
     * https://s3.wasabisys.com
     * http://localhost:9000
     */
    private String endpoint;

    /**
     * AWS region.
     */
    private String region = "us-east-1";

    /**
     * Bucket name.
     */
    private String bucket;

    /**
     * Access key.
     */
    private String accessKey;

    /**
     * Secret key.
     */
    private String secretKey;

    /**
     * Delete local files after successful upload.
     */
    private boolean deleteLocalAfterUpload = false;

    /**
     * Prefix inside bucket.
     * Example:
     * recordings/
     */
    private String prefix = "";
}
