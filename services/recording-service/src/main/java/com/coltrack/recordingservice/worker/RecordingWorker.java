package com.coltrack.recordingservice.worker;

import com.coltrack.recordingservice.model.RecordingSession;
import lombok.extern.slf4j.Slf4j;


@Slf4j
public class RecordingWorker implements Runnable {


    private final RecordingSession session;


    public RecordingWorker(
            RecordingSession session
    ) {

        this.session = session;
    }


    @Override
    public void run() {

        log.info(
                "Recording started camera={}",
                session.getCameraId()
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


    }
}
