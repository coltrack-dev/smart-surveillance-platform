export const API_URL =
    import.meta.env.VITE_API_URL ??
    `${window.location.protocol}//${window.location.hostname}:8080/api`;

export const HLS_URL =
    import.meta.env.VITE_HLS_URL ??
    `${window.location.protocol}//${window.location.hostname}:8080`;

export const WS_URL =
    import.meta.env.VITE_WS_URL ??
    `${window.location.protocol === "https:" ? "wss" : "ws"}://${window.location.hostname}:8080/ws`;
