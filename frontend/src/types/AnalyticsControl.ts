export type AnalyticsJobStatus =
    | "REQUESTED"
    | "RUNNING"
    | "RETRYING"
    | "STOP_REQUESTED"
    | "STOPPED"
    | "COMPLETED"
    | "FAILED"
    | "REJECTED";

export interface AnalyticsJob {
    jobId: string;
    cameraId: string;
    recordingId: string | null;
    jobType: "REALTIME" | "RECORDING";
    status: AnalyticsJobStatus;
    workerId: string | null;
    sourceUrl: string | null;
    sourceTransport: string | null;
    profile: Record<string, unknown>;
    details: Record<string, unknown>;
    createdAt: string;
    updatedAt: string;
    startedAt: string | null;
    finishedAt: string | null;
}

export interface AnalyticsProfileSettings {
    model: string;
    classes: number[];
    confidence: number;
    devicePreference: string;
    targetFps: number;
}

export interface RecordingAnalyticsStartRequest {
    cameraId: string;
    model?: string;
    classes: number[];
    confidence: number;
    devicePreference: string;
    linePosition: number;
    lines?: AnalyticsLine[];
    targetFps: number;
}

export interface RealtimeAnalyticsStartRequest {
    sourceUrl: string;
    transport: "tcp" | "udp";
    model?: string;
    classes: number[];
    confidence: number;
    devicePreference: string;
    linePosition: number;
    lines?: AnalyticsLine[];
    targetFps: number;
}

export interface AnalyticsPoint {
    x: number;
    y: number;
}

export interface AnalyticsLine {
    id: string;
    start: AnalyticsPoint;
    end: AnalyticsPoint;
    anchor: "BOTTOM_CENTER" | "CENTER";
    allowedDirections: Array<"A_TO_B" | "B_TO_A">;
    directionLabels: Record<string, string>;
    allowedClasses: number[];
    cooldownSeconds: number;
    hysteresis: number;
    minimumTrackAgeFrames: number;
}
