package com.coltrack.recordingservice.dto;

import java.time.LocalDate;

public record RecordingDateResponse(
        LocalDate date,
        long recordingsCount
) {

    public static RecordingDateResponse from(
            RecordingDateProjection projection
    ) {

        return new RecordingDateResponse(
                projection.getRecordingDate(),
                projection.getRecordingsCount()
        );
    }
}
