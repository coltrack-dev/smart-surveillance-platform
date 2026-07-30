
## Stream service

```txt
RTSP
 |
FFmpeg
 |
HLS
 |
Browser
```


---

### camera-service

```txt
GET    /api/cameras
GET    /api/cameras/{id}
POST   /api/cameras
PUT    /api/cameras/{id}
DELETE /api/cameras/{id}
```

---
### stream-service
```txt
GET    /api/streams
GET    /api/streams/{cameraId}
POST   /api/streams/{cameraId}/start
POST   /api/streams/{cameraId}/stop
GET    /api/streams/{cameraId}/url
DELETE /api/streams/{cameraId}
```
