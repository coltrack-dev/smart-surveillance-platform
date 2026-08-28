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

        function updateStream(event: StreamEvent): void {

            if (event.status === "STOPPED") {

                resetStream(
                    event.cameraId
                );

                return;
            }

            const old =
                streams.value[event.cameraId];

            streams.value[event.cameraId] = {

                cameraId: event.cameraId,
                status: event.status,
                hlsUrl: event.hlsUrl,
                error: event.error,
                startedAt: event.startedAt ?? old?.startedAt ?? null
            };

            console.log( event );

            /*
             * Только WebSocket событие
             * подтверждает запуск.
             */
            if (event.status === "RUNNING") {

                setStarting(
                    event.cameraId,
                    false
                );

            }
            else if (
                event.status === "OFFLINE" ||
                event.status === "ERROR"
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

        function resetStream(
            cameraId: string
        ): void {

            delete streams.value[cameraId];
            delete startingStreams.value[cameraId];

        }

        return {

            streams,

            updateStream,

            getStream,

            setStarting,

            isStarting,

            resetStream

        };

    }
);
