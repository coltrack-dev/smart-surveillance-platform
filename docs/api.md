
### Добавить камеру

```bash

curl -i -X POST "http://localhost:8080/api/v1/cameras" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "People Demo",
    "rtspUrl": "rtsp://mediamtx:8554/people",
    "autoStart": false,
    "favorite": false
  }'

```
---
