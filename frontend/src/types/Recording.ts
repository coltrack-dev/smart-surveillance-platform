export interface RecordingDate {
    date: string;
    recordingsCount: number;
}

export interface Recording {
    id: string;
    cameraId: string;

    startedAt: string;
    endedAt: string | null;

    durationSeconds: number | null;

    playbackUrl: string;
}
