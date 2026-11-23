import axios from "axios";
import { getAccessToken } from "@/auth/oidc";

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
    return config;
});

export default http;
