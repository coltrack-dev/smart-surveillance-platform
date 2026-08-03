export interface StreamEvent {

    cameraId: string;

    status: string;

    hlsUrl: string | null;

    error: string | null;

}
