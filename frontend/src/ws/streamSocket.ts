import { Client } from "@stomp/stompjs";

import type { Camera } from "@/types/Сamera";
import type { StreamEvent } from "@/types/StreamEvent";
import type {CameraStatusEvent} from "@/types/CameraStatusEvent.ts";

let client: Client | null = null;

export function connectStreamSocket(
    onStreamEvent: (event: StreamEvent) => void,
    onCameraEvent: (camera: Camera) => void,
    onCameraStatusEvent:(event: CameraStatusEvent) => void
): void {

    if (client?.active) {
        return;
    }

    client = new Client({

        //brokerURL: "ws://localhost:8094/ws",
        //brokerURL: "ws://localhost:8080/ws",
        brokerURL:
            import.meta.env.VITE_WS_URL ??
            `${window.location.protocol === "https:" ? "wss" : "ws"}://${window.location.hostname}:8080/ws`,

        reconnectDelay: 5000,

        debug(message) {
            console.log("STOMP:", message);
        },

        onConnect() {

            console.log("WebSocket connected");

            client!.subscribe(
                "/topic/streams",
                message => {

                    const event: StreamEvent = JSON.parse(message.body);

                    console.log("STREAM EVENT", event);

                    onStreamEvent(event);
                }
            );

            client!.subscribe(
                "/topic/cameras",
                message => {

                    const camera: Camera =
                        JSON.parse(message.body);

                    console.log("CAMERA EVENT", camera);
                    onCameraEvent(camera);
                }
            );

            client?.subscribe(
                "/topic/cameras/status",
                message => {

                    const event: CameraStatusEvent =
                        JSON.parse(message.body);

                    console.log("CAMERA STATUS EVENT", event);

                    onCameraStatusEvent(event);

                }
            );
        },

        onStompError(frame) {

            console.error("Broker error:", frame.headers["message"]);
            console.error(frame.body);
        },

        onWebSocketError(error) {

            console.error("WebSocket error", error);
        },

        onDisconnect() {

            console.log("WebSocket disconnected");
        }
    });

    client.activate();

}

export function disconnectStreamSocket(): void {

    if (client) {

        client.deactivate();

        client = null;

    }

}
