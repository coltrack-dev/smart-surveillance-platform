package com.coltrack.cameraservice.service;


import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.springframework.stereotype.Service;


@Slf4j
@Service
public class RtspStreamChecker {


    public boolean check(String url) {


        FFmpegFrameGrabber grabber = null;


        try {

            grabber = new FFmpegFrameGrabber(url);


            // таймаут подключения 5 секунд
            grabber.setOption(
                    "stimeout",
                    "5000000"
            );


            grabber.start();


            // проверяем что реально получили кадр
            var frame = grabber.grabImage();


            boolean available = frame != null;


            log.info(
                    "RTSP check {} result={}",
                    url,
                    available
            );


            return available;


        } catch (Exception e) {


            log.error(
                    "RTSP check failed: {}",
                    url,
                    e
            );


            return false;


        } finally {


            if (grabber != null) {

                try {

                    grabber.stop();

                } catch (Exception ignored) {

                }

            }

        }

    }

}
