package com.coltrack.recordingservice.worker;

import com.coltrack.recordingservice.model.RecordingSession;
import com.coltrack.recordingservice.model.RecordingStatus;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;


@Slf4j
public class RecordingWorker implements Runnable {

    private final RecordingSession session;

    public RecordingWorker(RecordingSession session) {

        this.session = session;
    }


    @Override
    public void run() {

        log.info(
                "Recording worker started camera={}",
                session.getCameraId()
        );

        try {

            session.setStatus(
                    RecordingStatus.RECORDING
            );

            session.setStartedAt(
                    Instant.now()
            );

            /*
             * Здесь позже будет FFmpeg:
             *
             * RTSP
             *   |
             *   v
             * FFmpeg
             *   |
             *   v
             * MP4 files
             */


            while (!session.isStopRequested()) {

                Thread.sleep(1000);
            }

            log.info(
                    "Recording stop requested camera={}",
                    session.getCameraId()
            );

            session.setStatus(
                    RecordingStatus.STOPPED
            );

            session.setFinishedAt(
                    Instant.now()
            );


        } catch (InterruptedException e) {

            Thread.currentThread()
                    .interrupt();

            log.warn(
                    "Recording interrupted camera={}",
                    session.getCameraId()
            );

        } catch (Exception e) {

            session.setStatus(
                    RecordingStatus.FAILED
            );

            log.error(
                    "Recording failed camera={}",
                    session.getCameraId(),
                    e
            );
        }

        log.info(
                "Recording worker finished camera={}",
                session.getCameraId()
        );
    }
}
