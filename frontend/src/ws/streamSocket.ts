import { Client } from "@stomp/stompjs";

let client: Client | null = null;

export function connectStreamSocket(
    onEvent: (event: any) => void
) {

    client = new Client({

        brokerURL:
            "ws://localhost:8094/ws",

        reconnectDelay: 5000,

        debug: (message) => {
            console.log(
                "STOMP:",
                message
            );
        },

        onConnect() {

            console.log(
                "WebSocket connected"
            );

            client?.subscribe(
                "/topic/streams",
                message => {

                    const event =
                        JSON.parse(
                            message.body
                        );

                    console.log(
                        "STREAM EVENT",
                        event
                    );

                    onEvent(event);
                }
            );
        }

    });

    client.activate();
}
