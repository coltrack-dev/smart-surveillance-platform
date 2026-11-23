# Smart Surveillance Platform — реестр тестов

Актуальность реестра: статические исходники, имеющиеся JUnit XML-отчёты и записи в `docs/broadcast-test-progress.md` / `docs/broadcast-integration-tests.md`. Тесты при составлении не запускались; Docker и Gradle не запускались.

## Метод подсчёта

- Java-реестр содержит один ряд на метод с `@Test`, с полным именем класса и метода. Assertions внутри метода отдельными тестами не считаются. Параметризованные случаи также не разворачиваются в отдельные методы. В найденных Java test sources аннотаций `@ParameterizedTest` нет.
- Статус `PASSED`, `FAILED` или `SKIPPED` берётся из JUnit XML, кроме 23 старых тестов stream-service: их последний полный отчёт и результат 23/23 PASS записаны в документации, но текущий Gradle запуск заменил набор XML-файлов отчётом только по целевому тесту. Целевой тест подтверждён его текущим XML и записью о запуске.
- `NOT RUN` означает, что среди доступных отчётов и журналов не найдено надёжного результата для метода. Это не утверждение, что метод никогда не запускался.

## Сводка Java/JUnit по модулю

| Модуль | Методов в исходниках | PASSED | FAILED | SKIPPED | NOT RUN |
|---|---:|---:|---:|---:|---:|
| camera-gateway | 1 | 1 | 0 | 0 | 0 |
| analytics-service | 24 | 22 | 0 | 0 | 2 |
| camera-service | 3 | 1 | 0 | 0 | 2 |
| notification-service | 1 | 0 | 0 | 0 | 1 |
| recording-service | 24 | 8 | 0 | 0 | 16 |
| search-service | 1 | 0 | 0 | 0 | 1 |
| stream-service | 24 | 24 | 0 | 0 | 0 |
| **Всего** | **78** | **56** | **0** | **0** | **22** |

В текущих JUnit XML на диске: **33 testcase-записей**, все PASS. Отчёты присутствуют для gateway (1), analytics-service (22), camera-service (1), recording-service (8) и целевого метода stream-service (1). Данные старого набора stream-service (23 PASS) подтверждены исторической записью; поэтому по исходникам stream-service в сумме 24 PASS.

## Полный список Java/JUnit методов

### camera-gateway

| Полное имя теста | Статус |
|---|---|
| `com.coltrack.gateway.GatewayApplicationTests.contextLoads` | **PASSED** |

### analytics-service

| Полное имя теста | Статус |
|---|---|
| `com.coltrack.analyticsservice.AnalyticsServiceApplicationTests.contextLoads` | **PASSED** |
| `com.coltrack.analyticsservice.dto.AnalyticsJobResponseTest.hidesStandardRtspPassword` | **PASSED** |
| `com.coltrack.analyticsservice.dto.AnalyticsJobResponseTest.hidesXmRtspPassword` | **PASSED** |
| `com.coltrack.analyticsservice.handler.AnalyticsEventHandlerRegistryTest.shouldDispatchEventToMatchingHandler` | **PASSED** |
| `com.coltrack.analyticsservice.handler.AnalyticsEventHandlerRegistryTest.shouldRejectDuplicateHandlerRegistration` | **PASSED** |
| `com.coltrack.analyticsservice.handler.AnalyticsEventHandlerRegistryTest.shouldRejectUnsupportedEventType` | **PASSED** |
| `com.coltrack.analyticsservice.handler.ObjectDetectedEventHandlerTest.shouldHandleObjectDetectedEvent` | **PASSED** |
| `com.coltrack.analyticsservice.handler.ObjectDetectedEventHandlerTest.shouldSupportObjectDetectedEvents` | **PASSED** |
| `com.coltrack.analyticsservice.service.AnalyticsControlServiceTest.realtimeStopMustTargetTheRunningJobId` | **PASSED** |
| `com.coltrack.analyticsservice.service.AnalyticsControlServiceTest.shouldAllowProgressUpdatesWhileRunning` | **PASSED** |
| `com.coltrack.analyticsservice.service.AnalyticsControlServiceTest.shouldCompleteRequestedStopWithStoppedStatus` | **PASSED** |
| `com.coltrack.analyticsservice.service.AnalyticsControlServiceTest.shouldCreateRecordingJobBeforePublishingIt` | **PASSED** |
| `com.coltrack.analyticsservice.service.AnalyticsControlServiceTest.shouldIgnoreCompletedWhileStopIsRequested` | **PASSED** |
| `com.coltrack.analyticsservice.service.AnalyticsControlServiceTest.shouldIgnoreRunningStatusAfterCompletion` | **PASSED** |
| `com.coltrack.analyticsservice.service.AnalyticsControlServiceTest.shouldResolveRealtimeSourceFromCameraServiceWhenOverrideIsEmpty` | **PASSED** |
| `com.coltrack.analyticsservice.service.AnalyticsControlServiceTest.shouldReturnConflictWhenDatabaseRejectsSecondActiveJob` | **PASSED** |
| `com.coltrack.analyticsservice.service.AnalyticsControlServiceTest.shouldReturnEmptyOptionalWhenRealtimeJobDoesNotExist` | **NOT RUN** |
| `com.coltrack.analyticsservice.service.AnalyticsControlServiceTest.shouldReturnEmptyOptionalWhenRecordingJobDoesNotExist` | **NOT RUN** |
| `com.coltrack.analyticsservice.service.AnalyticsEventServiceTest.shouldFindEventsForRecordingJob` | **PASSED** |
| `com.coltrack.analyticsservice.service.AnalyticsEventServiceTest.shouldFindPageContainingFirstEventAtVideoTime` | **PASSED** |
| `com.coltrack.analyticsservice.service.AnalyticsEventServiceTest.shouldReturnRecordingEventsForTimeline` | **PASSED** |
| `com.coltrack.analyticsservice.service.AnalyticsEventServiceTest.shouldSaveNewEvent` | **PASSED** |
| `com.coltrack.analyticsservice.service.AnalyticsEventServiceTest.shouldSkipEventInsertedConcurrently` | **PASSED** |
| `com.coltrack.analyticsservice.service.AnalyticsEventServiceTest.shouldSkipExistingEvent` | **PASSED** |

### camera-service

| Полное имя теста | Статус |
|---|---|
| `com.coltrack.cameraservice.CameraServiceApplicationTests.contextLoads` | **PASSED** |
| `com.coltrack.cameraservice.service.CameraCredentialsTest.encryptsAndDecryptsPassword` | **NOT RUN** |
| `com.coltrack.cameraservice.service.CameraCredentialsTest.resolvesXmTemplateWithoutPersistingPlaintextPassword` | **NOT RUN** |

### notification-service

| Полное имя теста | Статус |
|---|---|
| `com.coltrack.notificationservice.NotificationServiceApplicationTests.contextLoads` | **NOT RUN** |

### recording-service

| Полное имя теста | Статус |
|---|---|
| `com.coltrack.recordingservice.RecordingServiceApplicationTests.contextLoads` | **NOT RUN** |
| `com.coltrack.recordingservice.service.RecordingPlaybackServiceTest.refusesPlaybackWhenOnlyMissingS3CatalogRowsRemain` | **PASSED** |
| `com.coltrack.recordingservice.service.RecordingQueryServiceTest.protectsRecordingAndReportsHybridStorage` | **NOT RUN** |
| `com.coltrack.recordingservice.service.RecordingQueryServiceTest.rejectsInvalidTimeRange` | **NOT RUN** |
| `com.coltrack.recordingservice.service.RecordingQueryServiceTest.reportsMissingWhenOnlyUnverifiedCatalogRowsRemain` | **NOT RUN** |
| `com.coltrack.recordingservice.service.RecordingQueryServiceTest.reportsStorageCapacityAndCatalogedSize` | **NOT RUN** |
| `com.coltrack.recordingservice.service.RecordingStorageCleanupServiceTest.excludesLegacySharedDirectory` | **PASSED** |
| `com.coltrack.recordingservice.service.RecordingStorageCleanupServiceTest.excludesLocalOnlyRecordingByDefault` | **PASSED** |
| `com.coltrack.recordingservice.service.RecordingStorageCleanupServiceTest.previewsExpiredHybridRecordingWithoutDeletingIt` | **PASSED** |
| `com.coltrack.recordingservice.service.RecordingStorageCleanupServiceTest.reportsCleanupRequiredWhenDiskThresholdIsBreachedWithoutCandidates` | **PASSED** |
| `com.coltrack.recordingservice.service.RecordingStorageGuardTest.permitsRecordingBelowLimits` | **NOT RUN** |
| `com.coltrack.recordingservice.service.RecordingStorageGuardTest.rejectsNewRecordingAtLocalLimit` | **NOT RUN** |
| `com.coltrack.recordingservice.service.RecordingStoragePostgresIntegrationTest.advisoryLockRejectsConcurrentCleanupAcrossConnections` | **NOT RUN** |
| `com.coltrack.recordingservice.service.RecordingStoragePostgresIntegrationTest.migrationCreatesStorageSchemaAndRejectsDuplicateActiveS3Keys` | **NOT RUN** |
| `com.coltrack.recordingservice.service.RecordingStorageServiceTest.deletesOnlyDirectoryInsideConfiguredStorageRoot` | **NOT RUN** |
| `com.coltrack.recordingservice.service.RecordingStorageServiceTest.rejectsOverlappingThresholds` | **NOT RUN** |
| `com.coltrack.recordingservice.service.RecordingStorageServiceTest.resolvesHealthFromConfiguredThresholds` | **NOT RUN** |
| `com.coltrack.recordingservice.service.RecordingStorageServiceTest.snapshotMeasuresFilesRecursively` | **NOT RUN** |
| `com.coltrack.recordingservice.service.S3ReconciliationServiceTest.verifiesCatalogObjectsAndDetectsMissingObject` | **PASSED** |
| `com.coltrack.recordingservice.service.S3StorageManagementServiceTest.excludesHybridRecordingByDefault` | **NOT RUN** |
| `com.coltrack.recordingservice.service.S3StorageManagementServiceTest.previewsExpiredS3OnlyRecording` | **NOT RUN** |
| `com.coltrack.recordingservice.service.S3StorageManagementServiceTest.refusesDeletionUntilExplicitlyEnabled` | **NOT RUN** |
| `com.coltrack.recordingservice.service.S3StorageServiceTest.deletesEveryVersionAndDeleteMarker` | **PASSED** |
| `com.coltrack.recordingservice.service.S3StorageServiceTest.skipsPutWhenCatalogAndRemoteObjectAlreadyMatch` | **PASSED** |

### search-service

| Полное имя теста | Статус |
|---|---|
| `com.coltrack.searchservice.SearchServiceApplicationTests.contextLoads` | **NOT RUN** |

### stream-service

| Полное имя теста | Статус |
|---|---|
| `com.coltrack.streamservice.client.MediaHlsClientTest.rejectsExternalAndParentReferences` | **PASSED** |
| `com.coltrack.streamservice.client.MediaHlsClientTest.selectsMediaPlaylistAndLatestSegment` | **PASSED** |
| `com.coltrack.streamservice.config.IngestAgentPropertiesTest.buildsMediaMtxPublishAndPublicHlsUrls` | **PASSED** |
| `com.coltrack.streamservice.config.RtspStreamPropertiesTest.hasSafeNvrDefaults` | **PASSED** |
| `com.coltrack.streamservice.controller.BroadcastPlaybackControllerTest.capsConcurrentUpstreamRequestsAt32AndReleasesSlotsAfterCompletion` | **PASSED** |
| `com.coltrack.streamservice.controller.BroadcastPlaybackControllerTest.rejectsExternalPlaylistReferences` | **PASSED** |
| `com.coltrack.streamservice.controller.BroadcastPlaybackControllerTest.rejectsUnrecognizedKeyUriSyntaxInsteadOfLeavingItUnprotected` | **PASSED** |
| `com.coltrack.streamservice.controller.BroadcastPlaybackControllerTest.rewritesPlaylistSegmentsAndEncryptionKeyToProtectedUrls` | **PASSED** |
| `com.coltrack.streamservice.service.BroadcastServiceTest.enablingBroadcastAcquiresIndependentLeaseForExistingStream` | **PASSED** |
| `com.coltrack.streamservice.service.BroadcastServiceTest.privateBroadcastDoesNotReceiveAnonymousPlaybackToken` | **PASSED** |
| `com.coltrack.streamservice.service.BroadcastServiceTest.streamSessionJsonDoesNotExposeSourceRtspCredentials` | **PASSED** |
| `com.coltrack.streamservice.service.PlaybackTokenServiceTest.acceptsOnlyMatchingUnexpiredBroadcastToken` | **PASSED** |
| `com.coltrack.streamservice.service.StreamManagerLeaseTest.concurrentAcquireCallsReuseTheSameActiveSession` | **PASSED** |
| `com.coltrack.streamservice.service.StreamManagerLeaseTest.lastConsumerReleaseRequestsExactlyOneStop` | **PASSED** |
| `com.coltrack.streamservice.service.StreamManagerLeaseTest.legacyStopReleasesOnlyLegacyLease` | **PASSED** |
| `com.coltrack.streamservice.service.StreamManagerLeaseTest.releasingBroadcastLeaseDoesNotStopStreamWhileLegacyConsumerRemains` | **PASSED** |
| `com.coltrack.streamservice.worker.CameraStreamWorkerTest.emptyOrHeaderOnlyPlaylistIsNotReady` | **PASSED** |
| `com.coltrack.streamservice.worker.CameraStreamWorkerTest.playlistCannotReferenceFileOutsideStreamDirectory` | **PASSED** |
| `com.coltrack.streamservice.worker.CameraStreamWorkerTest.playlistIsNotReadyUntilReferencedSegmentExists` | **PASSED** |
| `com.coltrack.streamservice.worker.CameraStreamWorkerTest.usesWallclockTimestampsForRtspInput` | **PASSED** |
| `com.coltrack.streamservice.worker.IngestAgentStreamWorkerTest.doesNotPublishRunningBeforeHlsIsReady` | **PASSED** |
| `com.coltrack.streamservice.worker.IngestAgentStreamWorkerTest.mapsExistingVideoModesToAgentContract` | **PASSED** |
| `com.coltrack.streamservice.worker.IngestAgentStreamWorkerTest.recreatesMissingPipelineWithTheSameCommandId` | **PASSED** |
| `com.coltrack.streamservice.worker.IngestAgentStreamWorkerTest.retriesAfterTemporaryAgentCommunicationFailure` | **PASSED** |

## Python integration tests

Это standalone сценарии/скрипты, а не JUnit методы; проверки внутри скрипта не суммируются как отдельные методы. По существующим журналам и документации:

| Сценарий | Статус | Зафиксированный результат |
|---|---|---|
| `scripts/broadcast-integration-test.py` | **PASSED** | Регистрация камеры, отказ неавторизованного management доступа, идемпотентное создание, private-by-default, постоянные watch/embed URLs, tamper/expiry/revocation токена, безопасные пути, защищённые playlist/segments, совместное использование Broadcast и legacy Stream lease, disable/enable/delete. |
| `scripts/broadcast-mediamtx-auth-check.py` | **PASSED** | HTTP HLS/RTSP allow/deny; отсутствующие/неверные credentials отклонены; publisher может publish, reader не может; секреты не обнаружены в просмотренных логах. |
| `scripts/broadcast-recovery-test.py` | **PASSED (до перезагрузки хоста)** | Поочерёдный recovery после рестартов stream-service, ingest-agent, MediaMTX и PostgreSQL; защищённый HLS восстановился, по одному FFmpeg на source. |
| Отдельный camera network disconnect/reconnect | **NOT RUN** | Документация отмечает, что это не тестировалось независимо от перезапуска publisher/MediaMTX. |
| Recovery из состояния после host reboot | **UNKNOWN / NOT RUN** | После загрузки изолированная PostgreSQL была остановлена, camera-service перезапускался; post-boot playback не проверялся. |

## Playwright browser E2E

| Сценарий | Статус | Зафиксированный результат |
|---|---|---|
| `frontend` — `npm run test:broadcast:e2e` (Chromium, Playwright) | **PASSED** | Страница watch и защищённый iframe декодировали H.264 640×360; воспроизведение продвигалось через 90-секундный интервал обновления токена; старый токен получил 401; disable показал offline и запретил обновление токена; FFmpeg PID остался прежним; утечки токена в Referer/проверенных логах не обнаружены. |
| Четыре одновременных браузерных viewers | **PASSED** | В составе браузерного прогона; отдельный HTTP load результат ниже. |

## Нагрузочные и concurrency сценарии

| Сценарий | Статус | Измеренный/зафиксированный результат |
|---|---|---|
| 4 viewers, HTTP load, 30.23 секунды | **PASSED** | 128 запросов, 0 ошибок, 125080 байт; p50 6.56 ms, p95 11.04 ms, throughput 4.04 KiB/s. CPU/RAM именно финального запуска не записаны. |
| Live batches 32 / 33 / 96 сегментных запросов | **COMPLETED, INCONCLUSIVE** | Последние статусы: 32×200, 33×200, 96×200; предыдущие перемешанные 200/429 не доказывают насыщение semaphore. |
| `BroadcastPlaybackControllerTest.capsConcurrentUpstreamRequestsAt32AndReleasesSlotsAfterCompletion` | **PASSED** | Детерминированный unit-тест удерживал 32 upstream вызова, отклонял следующий запрос и принимал новый после освобождения слота. Запущен один фильтрованный метод; Gradle 8.14.2, один worker, без parallel; BUILD SUCCESSFUL, 1m25s. Этот сценарий уже выполнен и здесь не повторялся. |

## Почему 55, 78 и 23 — разные числа

В исходниках найдено **78 Java test methods** по всем включённым модулям. Исторический stream-service suite содержал **23 ранее пройденных метода**. Разность `78 − 23 = 55` — это оставшиеся методы вне того старого suite: 54 метода других модулей плюс новый semaphore test stream-service, которого ещё не было в старом наборе. Поэтому число 55 не означает 55 запусков или 55 пройденных тестов.

После добавления и отдельного запуска нового метода stream-service содержит 24 PASS. По имеющимся артефактам весь реестр: **56 PASSED, 0 FAILED, 0 SKIPPED, 22 NOT RUN**. Текущие XML подтверждают 33 PASS; ещё 23 PASS основаны на сохранённой исторической записи stream-service. Среди 55 методов, не входивших в прежние 23, подтверждены 33 PASS (32 из текущих XML других модулей и новый semaphore тест); остальные 22 помечены NOT RUN.

## Источники и ограничения

- Исходники: `*/src/test/java/**/*.java` в gateway и сервисных модулях.
- Текущие XML: `*/build/test-results/test/TEST-*.xml`; при снимке обнаружено 13 файлов с 33 testcase-записями. Stream-service старые подробные XML были заменены более поздним фильтрованным запуском.
- Исторический прогресс и отдельные интеграционные/браузерные/нагрузочные результаты описаны в `docs/broadcast-test-progress.md` и `docs/broadcast-integration-tests.md`. Их утверждения здесь приведены как сохранённые результаты, повторной проверки не было.
- `NOT RUN` в таблицах отражает только отсутствие надёжного результата в просмотренных артефактах.
