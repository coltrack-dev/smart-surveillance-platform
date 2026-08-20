import http from "@/api/http";
import type {
    AnalyticsEvent,
    AnalyticsEventFilters,
    AnalyticsEventsPage
} from "@/types/AnalyticsEvent";
import type {
    AnalyticsJob,
    RecordingAnalyticsStartRequest,
    RealtimeAnalyticsStartRequest
} from "@/types/AnalyticsControl";

interface AnalyticsEventsApiPage {
    content: AnalyticsEvent[];
    number?: number;
    size?: number;
    totalElements?: number;
    totalPages?: number;
    page?: {
        number: number;
        size: number;
        totalElements: number;
        totalPages: number;
    };
}

export async function startRecordingAnalytics(
    recordingId: string,
    request: RecordingAnalyticsStartRequest
): Promise<AnalyticsJob> {
    const response = await http.post<AnalyticsJob>(
        `/analytics/recordings/${recordingId}/start`,
        request
    );
    return response.data;
}

export async function findLatestRecordingAnalyticsJob(
    recordingId: string
): Promise<AnalyticsJob | null> {
    try {
        const response = await http.get<AnalyticsJob>(
            `/analytics/recordings/${recordingId}`
        );
        return response.data;
    } catch (error: unknown) {
        if (
            typeof error === "object"
            && error !== null
            && "response" in error
            && (error as { response?: { status?: number } }).response?.status === 404
        ) {
            return null;
        }
        throw error;
    }
}

export async function findAnalyticsJob(jobId: string): Promise<AnalyticsJob> {
    const response = await http.get<AnalyticsJob>(`/analytics/jobs/${jobId}`);
    return response.data;
}

export async function findAnalyticsEvents(
    filters: AnalyticsEventFilters
): Promise<AnalyticsEventsPage> {
    const response = await http.get<AnalyticsEventsApiPage>(
        "/analytics/events",
        { params: filters }
    );
    const data = response.data;
    const metadata = data.page;

    return {
        content: data.content,
        number: metadata?.number ?? data.number ?? 0,
        size: metadata?.size ?? data.size ?? filters.size ?? 20,
        totalElements: metadata?.totalElements ?? data.totalElements ?? 0,
        totalPages: metadata?.totalPages ?? data.totalPages ?? 0
    };
}

export async function findAnalyticsEvent(eventId: string): Promise<AnalyticsEvent> {
    const response = await http.get<AnalyticsEvent>(`/analytics/events/${eventId}`);
    return response.data;
}

export async function startRealtimeAnalytics(
    cameraId: string,
    request: RealtimeAnalyticsStartRequest
): Promise<AnalyticsJob> {
    const response = await http.post<AnalyticsJob>(
        `/analytics/realtime/${cameraId}/start`,
        request
    );
    return response.data;
}

export async function stopRealtimeAnalytics(cameraId: string): Promise<AnalyticsJob> {
    const response = await http.post<AnalyticsJob>(
        `/analytics/realtime/${cameraId}/stop`
    );
    return response.data;
}

export async function findLatestRealtimeAnalyticsJob(
    cameraId: string
): Promise<AnalyticsJob | null> {
    try {
        const response = await http.get<AnalyticsJob>(
            `/analytics/realtime/${cameraId}`
        );
        return response.data;
    } catch (error: unknown) {
        if (
            typeof error === "object"
            && error !== null
            && "response" in error
            && (error as { response?: { status?: number } }).response?.status === 404
        ) {
            return null;
        }
        throw error;
    }
}
