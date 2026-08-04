export type RecordingStatus =
    | "RECORDING"
    | "COMPLETED"
    | "FAILED"
    | "STOPPED";

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
