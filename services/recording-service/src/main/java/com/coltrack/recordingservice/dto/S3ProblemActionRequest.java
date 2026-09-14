package com.coltrack.recordingservice.dto;

import jakarta.validation.constraints.NotBlank;

public record S3ProblemActionRequest(@NotBlank String s3Key) {
}
