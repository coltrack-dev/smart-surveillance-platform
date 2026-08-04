export interface CameraStatusEvent {

    cameraId: string;

    status: string;

    reason: string | null;

    changedAt: string;

}
