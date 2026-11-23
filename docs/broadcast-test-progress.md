# Broadcast Phase 2.1 test recovery

## Current status

The host reboot interrupted a Gradle run after it started; no fresh result file exists for the new semaphore test. Earlier completed tests below remain complete and must not be repeated for confirmation.

No test, Compose start, or container recovery command has been run after the reboot. Docker automatically restarted part of the isolated `broadcast-it` project. At inspection, its PostgreSQL container was exited and `camera-service` was restarting; post-boot pipeline state is therefore UNKNOWN. The previous Surveillance deployment was not targeted.

## Scenario ledger

| Scenario | Classification | Evidence / notes | Further execution |
| --- | --- | --- | --- |
| MediaMTX 1.13.1 HTTP/RTSP authentication and role separation | PASSED | `scripts/broadcast-mediamtx-auth-check.py`: read credential got HTTP 200 and RTSP FFprobe succeeded; missing/wrong HTTP credentials returned 401; unauthenticated RTSP failed; publish role published; read-only role could not publish | Do not repeat |
| Media credentials in logs | PASSED | Same auth script scanned MediaMTX, ingest-agent, stream-service and synthetic publisher logs; no publish/read credential values found | Do not repeat |
| Browser HLS page and iframe decode | PASSED | Playwright Chromium 159.0.8154.6 decoded 640×360 H.264 in both; initial checks saw 10 frames each | Do not repeat |
| Permanent watch and iframe URLs | PASSED | Management playback response matched stable `/watch/{publicId}` and embed URL; generated iframe contained `referrerpolicy="no-referrer"` | Do not repeat |
| Token expiry, renewal, revocation | PASSED | TTL `PT75S`; playback time advanced 90 seconds through a 90-second interval; first token later returned 401; disable produced offline UI and refused token renewal | Do not repeat |
| Token/referrer leakage | PASSED | Browser observed no token-bearing Referer; first short-lived token and MediaMTX passwords were absent from checked container logs | Do not repeat |
| One FFmpeg per source / renewal continuity | PASSED | The final browser run saw one stable agent FFmpeg PID (`473327`) before and after token renewal | Do not repeat |
| Four-viewer HTTP load | PASSED | 4 viewers, 30.23 s, 128 requests, 0 failures, 125,080 bytes; p50 6.56 ms, p95 11.04 ms, 4.04 KiB/s | Do not repeat |
| Live 32/33/96 segment request batches | COMPLETED, INCONCLUSIVE | Final recorded batch statuses: 32×200, 33×200, 96×200. Earlier 96-request runs had mixed 200/429 responses while other requests overlapped; the 429s cannot be attributed to the semaphore | Do not repeat; this alone does not verify semaphore saturation |
| Stream-service, ingest-agent, MediaMTX and PostgreSQL restart recovery | PASSED before reboot | `scripts/broadcast-recovery-test.py` recovered protected HLS after each individual restart and counted one FFmpeg process | Do not repeat |
| Broadcast/legacy Stream lease sharing | PASSED | Disabling Broadcast did not stop the legacy Stream API consumer | Do not repeat |
| Prior stream-service unit suite | PASSED | Existing XML reports at 14:37: 23 tests, 0 failures/errors. The reports predate the new semaphore test | Do not repeat |
| New deterministic 32-slot controller test | PASSED | User-provided completed-run report: `BroadcastPlaybackControllerTest.capsConcurrentUpstreamRequestsAt32AndReleasesSlotsAfterCompletion`; Gradle 8.14.2; BUILD SUCCESSFUL in 1m25s; 13 tasks (2 executed, 11 up-to-date); only the filtered method ran; semaphore rejected request 33 while 32 upstream calls were held and admitted another request after release; Configuration Cache saved | Do not repeat |
| Initial post-reboot attempt before the completed run above | NOT RUN | Preflight found 21 GB disk free, 19 GiB available RAM, no swap use, but `/tmp/broadcast-phase21-gradle-home` did not exist at that time. No download was attempted. The user later reported a completed run using the requested cache/settings | Superseded by the PASSED test result above |
| Separate camera network disconnect/reconnect | NOT RUN | Not exercised independently of publisher/MediaMTX restarts | Optional remaining recovery scenario; require approval before running |
| CPU/RAM during the final browser load | NOT RECORDED | Final JSON output lacked the newer stats snapshots. Earlier snapshots are retained separately in `broadcast-integration-tests.md` | No repeat unless explicitly approved |
| Isolated stack state after host reboot | UNKNOWN | `docker ps -a` showed PostgreSQL exited and camera-service restarting; no post-boot playback check was run | Investigate only after approval; isolate all actions to project `broadcast-it` |

### Failed attempts that were superseded

- Initial Chromium requests were blocked by a mismatch between the test origin and gateway CORS origin. The isolated player was moved to the gateway's configured loopback origin.
- An early player build resolved the relative playlist URL against the frontend and received the SPA HTML fallback. The player now resolves media URLs against the API/gateway origin.
- An early browser attempt hit the pipeline before the first playlist was ready; the test now waits for the protected playlist before playback assertions.
- A Gradle attempt using the default `~/.gradle` failed immediately because that directory is read-only. The later isolated `/tmp` run is the interrupted run recorded above.

These failed attempts are not current acceptance failures; the later browser and authentication checks passed after the fixes.

## Reboot diagnosis

Read-only evidence collected after reboot:

- `journalctl --list-boots`: previous boot ended 2026-11-23 15:47:58 +07; current boot began 15:48:24 +07.
- Previous-boot journal shows `reboot.target`, `systemd-reboot.service` completion and orderly process termination. `sudo` and `systemd-logind` had no matching initiator record in the inspected interval; the request source is unknown.
- No kernel log matches for OOM kill, out-of-memory, panic, thermal shutdown, machine-check or watchdog lockup. The log contains VMware balloon/workqueue “hogged CPU for >10000us” warnings shortly before shutdown; these do not establish a cause.
- Current resources: `/` 21 GB free of 196 GB (89% used); RAM 25 GiB total, 22 GiB available, swap unused. Docker reports 20.22 GB images, 1.85 GB containers, 1.397 GB volumes and 285 MB build cache.
- No image, container, volume or cache was removed. No cleanup or test command was executed during diagnosis.

**Assessment:** high confidence this was an orderly software-requested reboot rather than an OOM, kernel panic or thermal shutdown. The initiating request and its relationship, if any, to the active Gradle process are unknown. Do not infer that tests caused the reboot.

## Remaining plan

The authorized deterministic semaphore test is now PASSED per the completed-run report above. The 23 earlier unit tests and all completed E2E/auth/load/restart checks remain untouched. The live request batches remain nondeterministic but are no longer the only evidence for the 32-slot bound. Camera disconnect/reconnect and post-host-reboot stack recovery remain untested; do not run them without separate approval. No Docker action has been taken.

## Working tree

The repository already contains broad uncommitted Phase 1/2 implementation changes and untracked data/model files. The progress documentation and test scripts are uncommitted. No files were reset, staged, committed or pushed during recovery.
