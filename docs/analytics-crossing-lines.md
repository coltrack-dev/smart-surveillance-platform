# Configurable line-crossing detection

Analytics jobs accept one or more normalized crossing lines. Coordinates use
the `0..1` range and therefore remain valid for different stream resolutions.

```json
{
  "lines": [
    {
      "id": "entrance",
      "start": { "x": 0.5, "y": 0.05 },
      "end": { "x": 0.5, "y": 0.95 },
      "anchor": "BOTTOM_CENTER",
      "allowedDirections": [],
      "directionLabels": {
        "A_TO_B": "RIGHT_TO_LEFT",
        "B_TO_A": "LEFT_TO_RIGHT"
      },
      "allowedClasses": [0],
      "cooldownSeconds": 5,
      "hysteresis": 0.02,
      "minimumTrackAgeFrames": 3
    }
  ]
}
```

The directed line from `start` to `end` divides the image into side A and side
B. Events contain both the stable direction code (`A_TO_B` or `B_TO_A`) and
the configured human-readable label. An empty `allowedDirections` list accepts
both directions.

`BOTTOM_CENTER` is recommended for people and vehicles. `CENTER` uses the
center of the detection box. Hysteresis creates a dead band around the line to
avoid repeated events when an object oscillates at the boundary.

Legacy `linePosition` requests remain supported and are converted to a full
horizontal line with `DOWN` and `UP` direction labels.
