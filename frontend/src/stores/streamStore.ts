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

            streams:
                {} as Record<string, StreamInfo>,

            starting:
                {} as Record<string, boolean>

        }),


        actions: {


            /**
             * Устанавливаем состояние запуска.
             */
            setStarting(
                cameraId: string,
                value: boolean
            ) {

                this.starting[cameraId] = value;

            },


            /**
             * WebSocket событие StreamStartedEvent.
             */
            updateStream(
                event: StreamInfo
            ) {

                this.streams[
                    event.cameraId
                    ] = event;


                // Поток готов, убираем spinner
                this.starting[
                    event.cameraId
                    ] = false;

            },


            removeStream(
                cameraId: string
            ) {

                delete this.streams[cameraId];

                delete this.starting[cameraId];

            }

        },


        getters: {


            getStream:
                (state) =>
                    (cameraId: string) =>
                        state.streams[cameraId],


            isStarting:
                (state) =>
                    (cameraId: string) =>
                        state.starting[cameraId] === true

        }

    }
);
