package com.coltrack.analyticsservice.service;

import com.coltrack.analyticsservice.config.AnalyticsSnapshotsProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AnalyticsSnapshotStorageService {

    private final S3Client analyticsSnapshotsS3Client;

    private final AnalyticsSnapshotsProperties properties;

    public SnapshotObject download(UUID eventId) {
        String key = buildKey(eventId);

        ResponseInputStream<GetObjectResponse> inputStream =
                analyticsSnapshotsS3Client.getObject(
                        GetObjectRequest.builder()
                                .bucket(properties.getBucket())
                                .key(key)
                                .build()
                );

        return new SnapshotObject(
                inputStream,
                inputStream.response().contentLength(),
                key
        );
    }

    private String buildKey(UUID eventId) {
        String prefix = properties.getPrefix() == null
                ? ""
                : properties
                  .getPrefix()
                  .replaceAll("^/+|/+$", "");

        if (prefix.isBlank()) {
            return eventId + ".jpg";
        }

        return prefix + "/" + eventId + ".jpg";
    }

    public record SnapshotObject(
            ResponseInputStream<GetObjectResponse> inputStream,
            Long contentLength,
            String key
    ) {
    }
}
