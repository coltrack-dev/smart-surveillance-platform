import axios from "axios";
import { getAccessToken, isOidcEnabled } from "@/auth/oidc";

const TENANT_STORAGE_KEY = "streaming.selectedTenant";

export function setSelectedTenant(tenantId: string | null): void {
    if (tenantId) sessionStorage.setItem(TENANT_STORAGE_KEY, tenantId);
    else sessionStorage.removeItem(TENANT_STORAGE_KEY);
}

export function getSelectedTenant(): string | null {
    return sessionStorage.getItem(TENANT_STORAGE_KEY);
}

//export default axios.create({
//    baseURL: import.meta.env.VITE_API_URL
//});

const http = axios.create({

    baseURL:
        import.meta.env.VITE_API_URL ??
        `${window.location.protocol}//${window.location.hostname}:8080/api`,

    timeout: 5000

});

http.interceptors.request.use(async (config) => {
    const token = await getAccessToken();
    if (token) config.headers.set("Authorization", "Bearer " + token);
    const url = config.url ?? "";
    if (isOidcEnabled() && /^\/(v1\/)?(cameras|streams|broadcasts)(\/|$)/.test(url)) {
        const tenantId = getSelectedTenant();
        if (tenantId) config.headers.set("X-Tenant-ID", tenantId);
    }
    return config;
});

http.interceptors.response.use(response => response, async (error: unknown) => {
    if (typeof error === "object" && error !== null && "response" in error) {
        const status = (error as { response?: { status?: number } }).response?.status;
        if (status === 401) window.dispatchEvent(new CustomEvent("streaming:session-expired"));
        if (status === 403 || status === 404) window.dispatchEvent(new CustomEvent("streaming:access-changed"));
    }
    return Promise.reject(error);
});

export default http;
