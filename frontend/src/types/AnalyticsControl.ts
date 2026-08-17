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

export interface RealtimeAnalyticsStartRequest {
    sourceUrl: string;
    transport: "tcp" | "udp";
    model?: string;
    classes: number[];
    confidence: number;
    devicePreference: string;
    linePosition: number;
    targetFps: number;
}
