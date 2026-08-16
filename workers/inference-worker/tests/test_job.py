import unittest

from inference_worker.job import (
    AnalyticsJob,
    UnsupportedAnalyticsJob,
)


class AnalyticsJobTest(unittest.TestCase):
    def test_converts_recording_ready_event(self) -> None:
        job = AnalyticsJob.from_message(
            {
                "eventId": "event-1",
                "eventType": "RECORDING_READY",
                "cameraId": "camera-1",
                "recordingId": "recording-1",
            }
        )

        self.assertEqual("event-1", job.job_id)
        self.assertEqual("RECORDING", job.job_type)
        self.assertEqual("camera-1", job.camera_id)
        self.assertEqual("recording-1", job.recording_id)
        self.assertEqual("RECORDING_SERVICE", job.source.type)
        self.assertEqual((0,), job.profile.classes)

    def test_parses_recording_analytics_job(self) -> None:
        job = AnalyticsJob.from_message(
            {
                "eventType": "ANALYTICS_JOB",
                "jobId": "job-1",
                "jobType": "RECORDING",
                "cameraId": "camera-1",
                "recordingId": "recording-1",
                "source": {
                    "type": "HTTP",
                    "url": "https://video.internal/source.mkv",
                },
                "profile": {
                    "model": "yolo11n.pt",
                    "classes": [0, 2],
                    "confidence": 0.4,
                    "devicePreference": "cuda:0",
                    "linePosition": 0.6,
                    "targetFps": 10,
                },
            }
        )

        self.assertEqual("job-1", job.job_id)
        self.assertEqual("HTTP", job.source.type)
        self.assertEqual(
            "https://video.internal/source.mkv",
            job.source.url,
        )
        self.assertEqual((0, 2), job.profile.classes)
        self.assertEqual(0.4, job.profile.confidence)
        self.assertEqual("cuda:0", job.profile.device_preference)
        self.assertEqual(0.6, job.profile.line_position)
        self.assertEqual(10.0, job.profile.target_fps)

    def test_parses_realtime_job_contract(self) -> None:
        job = AnalyticsJob.from_message(
            {
                "eventType": "ANALYTICS_JOB",
                "jobId": "job-live-1",
                "jobType": "REALTIME",
                "cameraId": "camera-1",
                "source": {
                    "type": "RTSP",
                    "url": "rtsp://video.internal:8554/people",
                    "transport": "TCP",
                },
                "profile": {
                    "classes": [0],
                    "targetFps": 10,
                },
            }
        )

        self.assertEqual("REALTIME", job.job_type)
        self.assertIsNone(job.recording_id)
        self.assertEqual("RTSP", job.source.type)
        self.assertEqual("TCP", job.source.transport)

    def test_rejects_unknown_message(self) -> None:
        with self.assertRaises(UnsupportedAnalyticsJob):
            AnalyticsJob.from_message(
                {
                    "eventType": "CAMERA_UPDATED",
                    "cameraId": "camera-1",
                }
            )


if __name__ == "__main__":
    unittest.main()
