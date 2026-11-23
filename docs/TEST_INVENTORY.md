# Smart Surveillance Platform — реестр тестов

## Phase 3.2C cross-transport additions and execution record (2026-11-23)

The previous Phase 3.2B source snapshot was 118 Java test methods. A current source scan finds **136 methods** across the repository (`@Test` methods; parameterized invocations and assertions are not expanded). Current source counts: camera-gateway 6, analytics-service 24, camera-service 12, notification-service 1, recording-service 25, search-service 1, stream-service 42, websocket-service 7, identity-service 5, common-security 13. The full per-method table and historical statuses below remain the Phase 3.2B snapshot; do not interpret it as a current 136-method execution report. Exact results for this phase are listed separately.

### Exact Phase 3.2C test results

| Full test method | Result | Evidence |
|---|---|---|
| `com.coltrack.identity.security.TenantWorkloadJwtTest.decoderRejectsModifiedSignatureAndMissingTenant` | PASSED | JUnit XML, 2026-11-23 |
| `com.coltrack.identity.security.TenantWorkloadJwtTest.ownershipLookupTokenHasNarrowScopeAndNoTenantClaim` | PASSED | JUnit XML, 2026-11-23 |
| `com.coltrack.identity.security.TenantWorkloadJwtTest.signedWorkloadTokenCarriesValidatedTenantContext` | PASSED | JUnit XML, 2026-11-23 |
| `com.coltrack.cameraservice.security.CameraInternalWorkloadSecurityTest.internalConnectionRequiresSignedWorkloadAndUsesSignedTenantInsteadOfHeader` | PASSED | JUnit XML, 2026-11-23 |
| `com.coltrack.cameraservice.security.CameraInternalWorkloadSecurityTest.ownershipLookupRequiresDedicatedServiceScopeAndReturnsPersistedOwner` | PASSED | JUnit XML, 2026-11-23 |
| `com.coltrack.cameraservice.service.CameraStatusTenantEventTest.streamingDoesNotMutateOrPublishStatusForUnassignedCamera` | PASSED | JUnit XML, 2026-11-23 |
| `com.coltrack.cameraservice.service.CameraStatusTenantEventTest.streamingStatusMutationUsesPersistedCameraOwner` | PASSED | JUnit XML, 2026-11-23 |
| `com.coltrack.websocketservice.security.TenantStompSecurityInterceptorTest.activeSubscriptionStopsReceivingFramesAfterMembershipRevocation` | PASSED | Final JUnit XML, 2026-11-23 |
| `com.coltrack.websocketservice.security.TenantStompSecurityInterceptorTest.authenticatedMultiTenantUserMayConnectPerTenantButCannotSubscribeAcrossTenant` | PASSED | Final JUnit XML, 2026-11-23 |
| `com.coltrack.websocketservice.security.TenantStompSecurityInterceptorTest.connectRequiresJwtAndValidatedMembershipForSelectedTenant` | PASSED | Final JUnit XML, 2026-11-23 |
| `com.coltrack.websocketservice.consumer.WebSocketEventTenantRoutingTest.cameraStatusRoutesUsingPersistedOwnerAndRedactsReason` | PASSED | Final JUnit XML, 2026-11-23 |
| `com.coltrack.websocketservice.consumer.WebSocketEventTenantRoutingTest.legacySurveillanceKeepsExistingGlobalDestinations` | PASSED | Final JUnit XML, 2026-11-23 |
| `com.coltrack.websocketservice.consumer.WebSocketEventTenantRoutingTest.streamStatusRoutesFromPersistedOwnerAndDoesNotForwardInternalMediaDetails` | PASSED | Final JUnit XML, 2026-11-23 |
| `com.coltrack.websocketservice.consumer.WebSocketEventTenantRoutingTest.unassignedAndUnknownKafkaResourcesAreNotBroadcast` | PASSED | Final JUnit XML, 2026-11-23 |
| `com.coltrack.streamservice.controller.BroadcastPlaybackHttpIsolationTest.privateGrantRevokedBeforeHttpProxyDoesNotReachMediaMtx` | PASSED | Filtered stream-service run; upstream mocked |
| `com.coltrack.streamservice.controller.BroadcastPlaybackHttpIsolationTest.authorizedHttpPlaylistProxyRewritesEveryMediaReference` | PASSED | Filtered stream-service run; upstream mocked |
| `com.coltrack.streamservice.controller.BroadcastPlaybackHttpIsolationTest.privateTokenHttpEndpointDoesNotIssueAnonymousGrant` | PASSED | Filtered stream-service run |
| `com.coltrack.streamservice.controller.BroadcastControllerTenantAuthorizationTest.viewerCannotEnableOrCreateBroadcastAndCannotAcquireBroadcastLease` | PASSED | Filtered stream-service run |
| `com.coltrack.streamservice.controller.BroadcastControllerTenantAuthorizationTest.managementMutationUsesSelectedValidatedTenantForObjectLookup` | PASSED | Filtered stream-service run |
| `com.coltrack.streamservice.service.BroadcastTenantOwnershipTest.creatingBroadcastChecksCameraThroughTenantDelegationAndPersistsOwner` | PASSED | Filtered stream-service run |
| `com.coltrack.streamservice.service.BroadcastTenantOwnershipTest.privatePlaybackIsRevokedWhenMembershipIsRevoked` | PASSED | Filtered stream-service run |
| `com.coltrack.streamservice.service.BroadcastTenantOwnershipTest.crossTenantBroadcastLookupUsesTenantQualifiedQueryAndReturnsNotFound` | PASSED | Filtered stream-service run |
| `com.coltrack.streamservice.service.BroadcastTenantOwnershipTest.creationDoesNotClaimUnassignedLegacyBroadcast` | PASSED | Filtered stream-service run |
| `com.coltrack.streamservice.service.BroadcastTenantOwnershipTest.unassignedPublicBroadcastIsNotPlayableInStreamingMode` | PASSED | Filtered stream-service run |
| `com.coltrack.streamservice.service.BroadcastTenantOwnershipTest.privatePlaybackGrantFromAnotherTenantIsRejected` | PASSED | Filtered stream-service run |
| `com.coltrack.streamservice.consumer.CameraEventsConsumerTenantTest.streamingIgnoresUnscopedCameraDeletionEvents` | PASSED | Filtered stream-service run |
| `com.coltrack.streamservice.consumer.CameraEventsConsumerTenantTest.streamingScopesCameraDeletionCleanupByTenant` | PASSED | Initial run exposed only a Mockito verification assertion; the corrected test method was run alone and passed |
| `com.coltrack.gateway.security.StreamingSecurityConfigurationTest.deniesLegacyMediaAndAllowsWebSocketHandshakeForStompLayerAuthorization` | PASSED | Filtered Gateway XML |

Run scope: common-security 3 methods; camera-service 4 methods; websocket-service 7 methods; stream-service 13 methods across four selected classes (12 passed initially; the one corrected assertion method then passed on its exact rerun); Gateway 1 method. Compile-only checks passed for websocket-service, camera-service, stream-service, camera-gateway and recording-service. Each Gradle invocation contained one module `Test` task and used `--no-daemon --no-parallel --max-workers=1` with `--tests`. No full regression, live broker, browser, HLS pipeline, load test, or existing deployment was run.

Static validation: both `docker compose ... config -q` commands for demo and streaming passed, as did `git diff --check`. Migration SQL was applied via `psql` to an isolated PostgreSQL 17 tmpfs container, not through service Flyway startup. Synthetic legacy Camera/Broadcast/lease rows remained unassigned and tenant-qualified lookup returned zero. Flyway history/startup and rollback remain BLOCKED. See `docs/saas/SECURITY_BOUNDARIES.md` for the security evidence matrix.

## Phase 3.2B additions and execution record (2026-11-23)

На последнем снимке перед Phase 3.2B реестр содержал 98 Java test methods. Добавлено 20 методов; текущее число исходных Java методов — **118**. Считаются методы, а не assertions или parameterized invocations.

| Module | Полное имя метода | Результат Phase 3.2B |
|---|---|---|
| common-security | `com.coltrack.identity.security.TenantPermissionsTest.ownerAndAdminCanManageResources` | **PASSED** |
| common-security | `com.coltrack.identity.security.TenantPermissionsTest.operatorCanOperateButViewerCannotModifyResources` | **PASSED** |
| common-security | `com.coltrack.identity.security.TenantWorkloadJwtTest.signedWorkloadTokenCarriesValidatedTenantContext` | **PASSED после исправления**; первая попытка падала из-за невалидного issuer формата URI. |
| common-security | `com.coltrack.identity.security.TenantWorkloadJwtTest.decoderRejectsModifiedSignatureAndMissingTenant` | **PASSED** в том же запуске, где первый метод упал. |
| camera-service | `com.coltrack.cameraservice.service.CameraTenantOwnershipTest.tenantLookupHidesUnknownAndOtherTenantCameras` | **PASSED** в фильтрованном запуске; XML был заменён последующим фильтрованным запуском. |
| camera-service | `com.coltrack.cameraservice.service.CameraTenantOwnershipTest.legacyLookupRemainsUnscopedForSurveillance` | **PASSED** в фильтрованном запуске; XML был заменён последующим фильтрованным запуском. |
| camera-service | `com.coltrack.cameraservice.service.CameraTenantOwnershipTest.streamingCreateTakesOwnerOnlyFromVerifiedServiceArgument` | **PASSED** в фильтрованном запуске; XML был заменён последующим фильтрованным запуском. |
| camera-service | `com.coltrack.cameraservice.controller.CameraControllerTenantAuthorizationTest.createAssignsTenantFromMembershipContext` | **PASSED** |
| camera-service | `com.coltrack.cameraservice.controller.CameraControllerTenantAuthorizationTest.missingMembershipDoesNotFallBackToUnscopedCameraLookup` | **PASSED** |
| camera-service | `com.coltrack.cameraservice.security.CameraInternalWorkloadSecurityTest.internalConnectionRequiresSignedWorkloadAndUsesSignedTenantInsteadOfHeader` | **PASSED после исправления fixture**; первый WebMvc slice не имел KafkaProperties bean. |
| stream-service | `com.coltrack.streamservice.controller.StreamTenantAuthorizationTest.viewerCannotStopCameraStream` | **PASSED** |
| stream-service | `com.coltrack.streamservice.controller.StreamTenantAuthorizationTest.tenantMemberCannotStopAnotherTenantsCamera` | **PASSED** |
| stream-service | `com.coltrack.streamservice.service.BroadcastTenantOwnershipTest.creatingBroadcastChecksCameraThroughTenantDelegationAndPersistsOwner` | **PASSED** |
| stream-service | `com.coltrack.streamservice.service.BroadcastTenantOwnershipTest.creationDoesNotClaimUnassignedLegacyBroadcast` | **PASSED**; exact-method run and directly related owner-create test both passed. |
| stream-service | `com.coltrack.streamservice.service.BroadcastTenantOwnershipTest.unassignedPublicBroadcastIsNotPlayableInStreamingMode` | **PASSED**; unassigned PUBLIC rows have no token path in OIDC mode. |
| stream-service | `com.coltrack.streamservice.service.BroadcastTenantOwnershipTest.privatePlaybackIsRevokedWhenMembershipIsRevoked` | **PASSED** |
| stream-service | `com.coltrack.streamservice.service.BroadcastTenantOwnershipTest.crossTenantBroadcastLookupUsesTenantQualifiedQueryAndReturnsNotFound` | **PASSED** |
| stream-service | `com.coltrack.streamservice.consumer.CameraEventsConsumerTenantTest.streamingIgnoresUnscopedCameraDeletionEvents` | **PASSED** |
| stream-service | `com.coltrack.streamservice.consumer.CameraEventsConsumerTenantTest.streamingScopesCameraDeletionCleanupByTenant` | **PASSED** |
| stream-service | `com.coltrack.streamservice.service.BroadcastServiceTest.legacySurveillanceStillAllowsEnabledPublicBroadcastPlayback` | **PASSED**; legacy constructor still supports enabled PUBLIC playback. |

### Повторно выполненные существующие методы

| Полное имя метода | Результат и причина |
|---|---|
| `com.coltrack.streamservice.controller.BroadcastPlaybackControllerTest.rewritesPlaylistSegmentsAndEncryptionKeyToProtectedUrls` | **PASSED**; изменился constructor fixture контроллера из-за TenantAuthorization. |
| `com.coltrack.streamservice.controller.BroadcastPlaybackControllerTest.rejectsExternalPlaylistReferences` | **PASSED**; Gradle запускал выбранный класс целиком. |
| `com.coltrack.streamservice.controller.BroadcastPlaybackControllerTest.rejectsUnrecognizedKeyUriSyntaxInsteadOfLeavingItUnprotected` | **PASSED**; Gradle запускал выбранный класс целиком. |
| `com.coltrack.streamservice.controller.BroadcastPlaybackControllerTest.capsConcurrentUpstreamRequestsAt32AndReleasesSlotsAfterCompletion` | **PASSED**; уже имел исторический PASS, но попал под class filter вместе с затронутым playback controller. Этот повтор был лишним и зафиксирован прозрачно. |
| `com.coltrack.identityservice.service.RolePermissionEvaluatorTest.deniesPermissionWhenTenantMembershipDoesNotExist` | **PASSED**; evaluator теперь использует общую permission matrix. |
| `com.coltrack.identityservice.service.RolePermissionEvaluatorTest.viewerCanViewPlaybackButCannotManageBroadcasts` | **PASSED**; evaluator теперь использует общую permission matrix. |
| `com.coltrack.streamservice.service.BroadcastServiceTest.privateBroadcastDoesNotReceiveAnonymousPlaybackToken` | **PASSED**; rerun by exact method because private playback authorization changed. |
| `com.coltrack.identity.security.TenantWorkloadJwtTest.decoderRejectsModifiedSignatureAndMissingTenant` | **PASSED**; exact-method rerun after correcting issuer format confirmed tampering is rejected. |
| `com.coltrack.streamservice.service.BroadcastTenantOwnershipTest.creatingBroadcastChecksCameraThroughTenantDelegationAndPersistsOwner` | **PASSED**; rerun alongside the new unassigned-row test after create logic was hardened. |

Каждый Gradle запуск содержал одну `Test` задачу одного модуля, с `--no-parallel --max-workers=1`; полный regression, Docker, браузерные, нагрузочные тесты и миграции не запускались. Фильтрованные Gradle запуски очищали XML предыдущего invocation этого модуля, поэтому часть ранее полученных поимённых XML больше не лежит на диске; таблица сохраняет зафиксированный вывод запуска и явно отмечает этот факт. Compile-only проверки `common-security`, `camera-service`, `stream-service`, `identity-service` прошли. Статическая проверка `docker compose ... config -q` для demo и streaming прошла; контейнеры не запускались.

### NOT RUN в Phase 3.2B

Camera list/update/delete/check-stream controller permutations; Broadcast management endpoint role/IDOR matrix; HTTP-level public/private token, expiry/tamper/revocation; PUBLIC/PRIVATE browser playback; миграции даже на изолированной БД; recovery с настоящими PostgreSQL/Kafka; WebSocket tenant authorization/fanout; Recording/Analytics isolation; широкие regression/integration suites. Исторические PASS не заменяют эти проверки.

Актуальность реестра: исходники и XML на 2026-11-23, а также исторические результаты в `docs/broadcast-test-progress.md` / `docs/broadcast-integration-tests.md`. Текущие Phase 3.2B дополнительные методы и запуски перечислены выше; предыдущие статусы остальных методов сохранены как исторические. Docker-сервисы не запускались.

## Метод подсчёта

- Java-реестр содержит один ряд на метод с `@Test`, с полным именем класса и метода. Assertions внутри метода отдельными тестами не считаются. Параметризованные случаи также не разворачиваются в отдельные методы. В найденных Java test sources аннотаций `@ParameterizedTest` нет.
- Статус `PASSED`, `FAILED` или `SKIPPED` берётся из JUnit XML либо сохранённых исторических отчётов. 24 исходных stream-service метода получили PASS в ограниченном повторном запуске Phase 3.2A; при последующем узком запуске XML был заменён отчётом только по новому security test class. 22 ранее NOT RUN метода других модулей повторно не запускались.
- `NOT RUN` означает, что среди доступных отчётов и журналов не найдено надёжного результата для метода. Это не утверждение, что метод никогда не запускался.

## Сводка Java/JUnit по модулю

| Модуль | Методов в исходниках | PASSED | FAILED | SKIPPED | NOT RUN |
|---|---:|---:|---:|---:|---:|
| camera-gateway | 6 | 6 | 0 | 0 | 0 |
| analytics-service | 24 | 22 | 0 | 0 | 2 |
| camera-service | 9 | 7 | 0 | 0 | 2 |
| notification-service | 1 | 0 | 0 | 0 | 1 |
| recording-service | 24 | 8 | 0 | 0 | 16 |
| search-service | 1 | 0 | 0 | 0 | 1 |
| stream-service | 36 | 36 | 0 | 0 | 0 |
| common-security | 12 | 12 | 0 | 0 | 0 |
| identity-service | 5 | 5 | 0 | 0 | 0 |
| **Всего** | **118** | **96** | **0** | **0** | **22** |

На диске JUnit XML для нескольких затронутых модулей заменены последовательными фильтрованными запусками; точный текущий охват и утраченные отчёты описаны в Phase 3.2B execution record выше. Сводная таблица объединяет сохранённые исторические PASS, новые подтверждённые PASS и 22 NOT RUN; это не означает, что все 93 PASS были выполнены в текущем запуске.

## Полный список Java/JUnit методов

### camera-gateway

| Полное имя теста | Статус |
|---|---|
| `com.coltrack.gateway.GatewayApplicationTests.contextLoads` | **PASSED** |
| `com.coltrack.gateway.security.LegacySecurityConfigurationTest.preservesUnauthenticatedTrustedSurveillanceGatewayAccess` | **PASSED** |
| `com.coltrack.gateway.security.StreamingSecurityConfigurationTest.requiresAuthenticationForManagementRoutes` | **PASSED** |
| `com.coltrack.gateway.security.StreamingSecurityConfigurationTest.authenticatedJwtCanReachManagementRoutes` | **PASSED** |
| `com.coltrack.gateway.security.StreamingSecurityConfigurationTest.deniesLegacyMediaAndWebSocketRoutes` | **PASSED** |
| `com.coltrack.gateway.security.StreamingSecurityConfigurationTest.permitsPublicPlaybackRoutesToReachBroadcastPolicy` | **PASSED** |

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
| `com.coltrack.streamservice.security.StreamingResourceSecurityConfigurationTest.requiresAuthenticationForManagementRoutes` | **PASSED** |
| `com.coltrack.streamservice.security.StreamingResourceSecurityConfigurationTest.permitsPlaybackPolicyRoutesAndRequiresTheirOwnGrant` | **PASSED** |

### common-security

| Полное имя теста | Статус |
|---|---|
| `com.coltrack.security.OidcJwtValidatorsTest.acceptsTokenWithExpectedIssuerAudienceAndLifetime` | **PASSED** |
| `com.coltrack.security.OidcJwtValidatorsTest.rejectsWrongIssuer` | **PASSED** |
| `com.coltrack.security.OidcJwtValidatorsTest.rejectsMissingAudience` | **PASSED** |
| `com.coltrack.security.OidcJwtValidatorsTest.rejectsExpiredToken` | **PASSED** |
| `com.coltrack.security.OidcJwtValidatorsTest.rejectsTokenBeforeNotBeforeTime` | **PASSED** |
| `com.coltrack.security.OidcJwtSignatureTest.validatesRsaSignatureAndRejectsModifiedSignature` | **PASSED** |
| `com.coltrack.identity.security.MembershipResolverTest.tenantSelectorDoesNotCreateMembership` | **PASSED** |
| `com.coltrack.identity.security.MembershipResolverTest.resolvedTenantContextComesFromMembership` | **PASSED** |

### identity-service

| Полное имя теста | Статус |
|---|---|
| `com.coltrack.identityservice.controller.IdentityControllerSecurityTest.rejectsAnonymousIdentityDiscovery` | **PASSED** |
| `com.coltrack.identityservice.controller.IdentityControllerSecurityTest.returnsMembershipsForValidatedJwtPrincipal` | **PASSED** |
| `com.coltrack.identityservice.controller.IdentityControllerSecurityTest.returnsDiscoveredUserFromAuthenticatedIdentity` | **PASSED** |
| `com.coltrack.identityservice.service.RolePermissionEvaluatorTest.deniesPermissionWhenTenantMembershipDoesNotExist` | **PASSED** |
| `com.coltrack.identityservice.service.RolePermissionEvaluatorTest.viewerCanViewPlaybackButCannotManageBroadcasts` | **PASSED** |

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

Это арифметика более раннего снимка реестра, а не текущий подсчёт. Тогда в исходниках было **78 Java test methods**, а сохранённый прежний stream-service suite содержал **23 PASS**. Разность `78 − 23 = 55` обозначала все остальные исходные методы, не количество запусков и не число PASS. Затем Phase 3.2A добавила методы и довела снимок до 98; Phase 3.2B добавила ещё 17, поэтому сейчас в исходниках 115 методов.

На снимке после Phase 2.1 весь реестр оценивался как 56 PASS и 22 NOT RUN. После последующих проверок и Phase 3.2B текущая сводка составляет **96 PASSED, 0 FAILED, 0 SKIPPED, 22 NOT RUN**. Из этих 96 PASS 20 относятся к новым Phase 3.2B методам; остальные — исторические результаты, включая тесты, которые не запускались в этой задаче.

## Источники и ограничения

- Исходники: `*/src/test/java/**/*.java` в gateway, common и сервисных модулях.
- Текущие XML: `*/build/test-results/test/TEST-*.xml`; точечные запуски Phase 3.2B заменили результаты XML в изменённых модулях, поэтому полный текущий per-test XML снимок недоступен. Статусы из запусков сохранены выше; исторические результаты помечены отдельно.
- Исторический прогресс и отдельные интеграционные/браузерные/нагрузочные результаты описаны в `docs/broadcast-test-progress.md` и `docs/broadcast-integration-tests.md`. Их утверждения здесь приведены как сохранённые результаты, повторной проверки не было.
- `NOT RUN` в таблицах отражает только отсутствие надёжного результата в просмотренных артефактах.
