import http from "@/api/http";
import type {
    AnalyticsEvent,
    AnalyticsEventFilters,
    AnalyticsEventsPage
} from "@/types/AnalyticsEvent";

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
