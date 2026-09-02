package com.coltrack.recordingservice.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

public record RecordingProtectionRequest(
        @NotNull
        @JsonProperty("protected")
        Boolean protectedFromDeletion
) {
}
