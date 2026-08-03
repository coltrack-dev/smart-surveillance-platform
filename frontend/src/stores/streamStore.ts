import { defineStore } from "pinia";

export interface StreamInfo {
    cameraId: string;
    hlsUrl: string;
    startedAt: string;
}

export const useStreamStore = defineStore(
    "streams",
    {
        state: () => ({
            streams: {} as Record<string, StreamInfo>
        }),

        actions: {

            updateStream(
                event: StreamInfo
            ) {

                this.streams[
                    event.cameraId
                    ] = event;

            },

            removeStream(
                cameraId: string
            ) {

                delete this.streams[cameraId];

            }

        },

        getters: {

            getStream:
                (state) =>
                    (cameraId: string) =>
                        state.streams[cameraId]

        }
    }
);
