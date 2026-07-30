package com.coltrack.cameraservice.service;

import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.springframework.stereotype.Service;

import java.util.concurrent.*;

@Slf4j
@Service
public class RtspStreamChecker {

    /*
     * Временный executor для ручной проверки RTSP потока.
     *
     * Не использовать для постоянного мониторинга камер.
     * В будущем состояние камеры должно определяться через stream-service
     * и lastFrameTime активной StreamSession.
     */
    private final ExecutorService executor =
            Executors.newFixedThreadPool(4);

    /*
     * Проверка доступности RTSP потока.
     *
     * Используется для:
     * - ручной проверки камеры;
     * - административного endpoint /check-stream.
     *
     * Не использовать в Scheduler для большого количества камер.
     */
    public boolean check(String url) {

        Future<Boolean> future =
                executor.submit(() -> checkInternal(url));

        try {

            return future.get(
                    20,
                    TimeUnit.SECONDS
            );

        } catch (TimeoutException e) {

            future.cancel(true);

            log.error(
                    "RTSP timeout: {}",
                    url
            );

            return false;

        } catch (Exception e) {

            log.error(
                    "RTSP check error: {}",
                    url,
                    e
            );

            return false;
        }
    }

    /*
     * Реальная проверка RTSP соединения через FFmpeg.
     */
    private boolean checkInternal(String url) {

        FFmpegFrameGrabber grabber = null;

        try {

            log.info(
                    "RTSP connecting: {}",
                    url
            );

            grabber =
                    new FFmpegFrameGrabber(url);

            /*
             * Используем TCP транспорт.
             *
             * UDP часто приводит к проблемам:
             * - потеря пакетов;
             * - зависание подключения;
             * - проблемы через VPN/NAT.
             */
            grabber.setOption(
                    "rtsp_transport",
                    "tcp"
            );

            /*
             * Timeout подключения RTSP.
             * Значение в микросекундах.
             */
            grabber.setOption(
                    "stimeout",
                    "15000000"
            );

            grabber.start();

            log.info(
                    "RTSP connected: {}",
                    url
            );

            /*
             * Получаем первый кадр.
             * Если кадр получен, камера считается доступной.
             */
            boolean available =
                    grabber.grabImage() != null;

            log.info(
                    "RTSP frame received={} url={}",
                    available,
                    url
            );

            return available;

        } catch (Exception e) {

            log.error(
                    "RTSP failed: {}",
                    url,
                    e
            );

            return false;

        } finally {

            if (grabber != null) {

                try {

                    grabber.stop();

                } catch (Exception e) {

                    log.debug(
                            "Failed to stop grabber",
                            e
                    );
                }
            }
        }
    }
}
