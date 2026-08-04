package com.coltrack.recordingservice.dto;

import java.time.LocalDate;

public interface RecordingDateProjection {

    LocalDate getRecordingDate();

    long getRecordingsCount();
}
