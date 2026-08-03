export interface Camera {

    id: string;

    cameraNumber: number;

    name: string;

    rtspUrl: string;

    status: string;

    createdAt: string;

    lastHeartbeat: string | null;

    autoStart: boolean;

    lastError: string | null;

    lastStatusChangedAt: string | null;

    lbsLocation: unknown | null;

    category: unknown | null;
}

export type CameraStatus =
    | "ONLINE"
    | "OFFLINE"
    | "ERROR"
    | "UNKNOWN";

export interface CameraPage {

    content: Camera[];
    totalPages: number;
    totalElements: number;
    size: number;
    number: number;
    first: boolean;
    last: boolean;
    numberOfElements: number;
}
