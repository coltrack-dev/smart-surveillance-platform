import unittest

from inference_worker.job import AnalyticsJob, stop_targets_running_job


def job(
    job_id: str,
    *,
    job_type: str = "RECORDING",
    camera_id: str = "camera-1",
    recording_id: str | None = "recording-1",
    action: str = "START",
) -> AnalyticsJob:
    return AnalyticsJob.from_message(
        {
            "eventType": "ANALYTICS_JOB",
            "jobId": job_id,
            "jobType": job_type,
            "action": action,
            "cameraId": camera_id,
            "recordingId": recording_id,
            "source": {
                "type": "RTSP" if job_type == "REALTIME" else "RECORDING_SERVICE",
                "url": "rtsp://camera/live" if job_type == "REALTIME" else None,
            },
            "profile": {"classes": [0]},
        }
    )


class StopTargetTest(unittest.TestCase):
    def test_stop_matches_exact_recording_job(self) -> None:
        running = job("job-1")
        stop = job("job-1", action="STOP")

        self.assertTrue(stop_targets_running_job(stop, running))

    def test_delayed_stop_does_not_match_new_recording_job(self) -> None:
        running = job("job-new")
        delayed_stop = job("job-old", action="STOP")

        self.assertFalse(stop_targets_running_job(delayed_stop, running))

    def test_delayed_stop_does_not_match_new_realtime_job(self) -> None:
        running = job(
            "job-new",
            job_type="REALTIME",
            recording_id=None,
        )
        delayed_stop = job(
            "job-old",
            job_type="REALTIME",
            recording_id=None,
            action="STOP",
        )

        self.assertFalse(stop_targets_running_job(delayed_stop, running))


if __name__ == "__main__":
    unittest.main()
