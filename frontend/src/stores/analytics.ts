import { defineStore } from "pinia";
import { ref } from "vue";
import { findAnalyticsEvents } from "@/api/analyticsApi";
import type { AnalyticsEvent, AnalyticsEventFilters } from "@/types/AnalyticsEvent";

export const useAnalyticsStore = defineStore("analytics", () => {
    const events = ref<AnalyticsEvent[]>([]);
    const loading = ref(false);
    const error = ref<string | null>(null);
    const currentPage = ref(0);
    const pageSize = ref(20);
    const totalElements = ref(0);
    const totalPages = ref(0);

    async function load(filters: AnalyticsEventFilters = {}): Promise<void> {
        loading.value = true;
        error.value = null;

        try {
            const result = await findAnalyticsEvents({
                page: currentPage.value,
                size: pageSize.value,
                sort: "occurredAt,desc",
                ...filters
            });

            events.value = result.content;
            currentPage.value = result.number;
            pageSize.value = result.size;
            totalElements.value = result.totalElements;
            totalPages.value = result.totalPages;
        } catch (reason) {
            events.value = [];
            error.value = reason instanceof Error
                ? reason.message
                : "Не удалось загрузить события аналитики";
        } finally {
            loading.value = false;
        }
    }

    return {
        events,
        loading,
        error,
        currentPage,
        pageSize,
        totalElements,
        totalPages,
        load
    };
});
