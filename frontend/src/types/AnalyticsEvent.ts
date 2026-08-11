export interface AnalyticsEvent {
    eventId: string;
    schemaVersion: number;
    eventType: string;
    cameraId: string;
    trackId: number | null;
    objectType: string | null;
    confidence: number | null;
    frameNumber: number | null;
    videoTimeSeconds: number | null;
    occurredAt: string;
    receivedAt: string;
    recordingId: string | null;
    snapshotUrl: string | null;
    clipUrl: string | null;
    attributes: Record<string, unknown>;
}

export interface AnalyticsEventFilters {
    cameraId?: string;
    eventType?: string;
    objectType?: string;
    from?: string;
    to?: string;
    page?: number;
    size?: number;
    sort?: string;
}

export interface AnalyticsEventsPage {
    content: AnalyticsEvent[];
    number: number;
    size: number;
    totalElements: number;
    totalPages: number;
}
