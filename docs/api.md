
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


curl -i -X POST \
  "http://localhost:8080/api/v1/cameras" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "I-287 Route 119",
    "rtspUrl": "rtsp://mediamtx:8554/i287",
    "autoStart": false,
    "favorite": false
  }'
```
---
### Список камер
```bash
curl -s \
"http://localhost:8080/api/v1/cameras?size=100" |
jq
```

