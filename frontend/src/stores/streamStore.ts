import { defineStore } from "pinia";
import { ref } from "vue";

import type { StreamEvent } from "@/types/StreamEvent";


export interface StreamInfo {

    cameraId: string;

    status: string;

    hlsUrl: string | null;

    error: string | null;

    startedAt: string | null;

}


export const useStreamStore = defineStore(
    "stream",
    () => {

        const streams = ref<Record<string, StreamInfo>>({});


        /**
         * Потоки, которые ожидают запуска.
         * После StreamStartedEvent снимаются.
         */
        const startingStreams = ref<Record<string, boolean>>({});


        function updateStream(
            event: StreamEvent
        ): void {

            const old =
                streams.value[event.cameraId];


            streams.value[event.cameraId] = {

                cameraId: event.cameraId,

                status: event.status,

                hlsUrl: event.hlsUrl,

                error: event.error,

                startedAt:
                    old?.startedAt ?? null

            };


            /*
             * Только WebSocket событие
             * подтверждает запуск.
             */
            if (
                event.status === "RUNNING"
            ) {

                setStarting(
                    event.cameraId,
                    false
                );

            }


            if (
                event.status === "ERROR" ||
                event.status === "STOPPED"
            ) {

                setStarting(
                    event.cameraId,
                    false
                );

            }

        }


        function setStarting(
            cameraId: string,
            value: boolean
        ): void {

            startingStreams.value[cameraId] = value;

        }


        function isStarting(
            cameraId: string
        ): boolean {

            return (
                startingStreams.value[cameraId]
                ?? false
            );

        }


        function getStream(
            cameraId: string
        ): StreamInfo | undefined {

            return streams.value[cameraId];

        }


        return {

            streams,

            updateStream,

            getStream,

            setStarting,

            isStarting

        };

    }
);
