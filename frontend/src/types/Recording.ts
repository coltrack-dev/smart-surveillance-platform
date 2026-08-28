export interface RecordingDate {
    date: string;
    recordingsCount: number;
}
export type RecordingStatus =
    | "STARTING"
    | "RECORDING"
    | "STOPPING"
    | "COMPLETED"
    | "FAILED"
    | "STOPPED";

export interface ActiveRecording {
    id: string;
    cameraId: string;
    status: RecordingStatus;
    startedAt: string | null;
    finishedAt: string | null;
    lastError: string | null;
    durationSeconds: number | null;
    sizeBytes: number | null;
}

export interface Recording {

    id: string;

    cameraId: string;

    startedAt: string;

    finishedAt: string | null;

    durationSeconds: number | null;

    sizeBytes: number | null;

    status: RecordingStatus;

    playbackUrl: string;
}
