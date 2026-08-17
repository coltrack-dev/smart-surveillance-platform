import unittest

from inference_worker.frame_buffer import FrameEnvelope, LatestFrameBuffer


class LatestFrameBufferTest(unittest.TestCase):
    def test_returns_each_sequence_once(self) -> None:
        buffer = LatestFrameBuffer()
        frame = object()
        buffer.replace(FrameEnvelope(1, 1.0, frame))

        self.assertIs(frame, buffer.take_fresh().frame)
        self.assertIsNone(buffer.take_fresh())

    def test_replaces_stale_frame_and_counts_drop(self) -> None:
        buffer = LatestFrameBuffer()
        first = object()
        second = object()
        buffer.replace(FrameEnvelope(1, 1.0, first))
        buffer.replace(FrameEnvelope(2, 2.0, second))

        self.assertIs(second, buffer.take_fresh().frame)
        self.assertEqual(1, buffer.dropped)

    def test_replacement_after_take_is_not_a_drop(self) -> None:
        buffer = LatestFrameBuffer()
        buffer.replace(FrameEnvelope(1, 1.0, object()))
        buffer.take_fresh()
        buffer.replace(FrameEnvelope(2, 2.0, object()))

        self.assertEqual(0, buffer.dropped)


if __name__ == "__main__":
    unittest.main()
