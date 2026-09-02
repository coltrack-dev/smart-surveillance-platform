package com.coltrack.recordingservice.dto;

import java.util.List;

public record RecordingPageResponse(
        List<RecordingResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
}
