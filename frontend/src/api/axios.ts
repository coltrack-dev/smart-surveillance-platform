import axios from "axios";
import { getAccessToken } from "@/auth/oidc";

const apiUrl =
    import.meta.env.VITE_API_URL ??
    `${window.location.protocol}//${window.location.hostname}:8080/api`;

const api = axios.create({

    baseURL: apiUrl,

    timeout: 5000

});

api.interceptors.request.use(async (config) => {
    const token = await getAccessToken();
    if (token) config.headers.set("Authorization", "Bearer " + token);
    return config;
});

export default api;
