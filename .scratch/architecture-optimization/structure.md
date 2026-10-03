# 最终生产类型与包依赖核对

方案中的 74 个现有类型各有唯一目标；新增 runtime 与核心配置后 76 个。原有辅助类型没有新增 public。生产 import 无 wildcard。静态包依赖无循环；该检查不替代设施验收。

| 原路径 | 实际目标 |
| --- | --- |
| `api/ApiError.java` | `api/error/ApiError.java` |
| `api/ApiExceptionHandler.java` | `api/error/ApiExceptionHandler.java` |
| `api/CreateCacheCoordinationError.java` | `api/error/CreateCacheCoordinationError.java` |
| `api/CreateLinkRequest.java` | `api/CreateLinkRequest.java` |
| `api/CreateLinkResponse.java` | `api/CreateLinkResponse.java` |
| `api/EnabledStateResponse.java` | `api/EnabledStateResponse.java` |
| `api/InternalManagement.java` | `api/management/InternalManagement.java` |
| `api/InternalManagementAccess.java` | `api/management/InternalManagementAccess.java` |
| `api/InternalManagementWebConfiguration.java` | `api/management/InternalManagementWebConfiguration.java` |
| `api/SetEnabledRequest.java` | `api/SetEnabledRequest.java` |
| `api/SetEnabledRequestDeserializer.java` | `api/SetEnabledRequestDeserializer.java` |
| `api/ShortLinkController.java` | `api/ShortLinkController.java` |
| `api/StateCacheCoordinationError.java` | `api/error/StateCacheCoordinationError.java` |
| `api/StatsDateParameters.java` | `api/stats/StatsDateParameters.java` |
| `api/ValidMinutesDeserializer.java` | `api/ValidMinutesDeserializer.java` |
| `api/VisitCollection.java` | `api/stats/VisitCollection.java` |
| `api/VisitCursorCodec.java` | `api/stats/VisitCursorCodec.java` |
| `api/VisitPageResponse.java` | `api/stats/VisitPageResponse.java` |
| `api/VisitStatsController.java` | `api/stats/VisitStatsController.java` |
| `api/VisitStatsResponse.java` | `api/stats/VisitStatsResponse.java` |
| `cache/RedirectCache.java` | `cache/RedirectCache.java` |
| `cache/RedirectCacheEntry.java` | `cache/RedirectCacheEntry.java` |
| `cache/RedirectCacheProperties.java` | `cache/RedirectCacheProperties.java` |
| `cache/RedirectCacheRead.java` | `cache/RedirectCacheRead.java` |
| `cache/RedisRedirectCache.java` | `cache/RedisRedirectCache.java` |
| `LinkApplication.java` | `LinkApplication.java` |
| `logging/SafeDependencyConsoleEncoder.java` | `logging/SafeDependencyConsoleEncoder.java` |
| `persistence/MySqlShortCodeIdIssuer.java` | `persistence/MySqlShortCodeIdIssuer.java` |
| `persistence/MySqlShortLinkWriter.java` | `persistence/MySqlShortLinkWriter.java` |
| `persistence/ShortCodeCollisionException.java` | `persistence/ShortCodeCollisionException.java` |
| `persistence/ShortLinkEntity.java` | `persistence/ShortLinkEntity.java` |
| `persistence/ShortLinkMapper.java` | `persistence/ShortLinkMapper.java` |
| `service/CreatedShortLink.java` | `service/CreatedShortLink.java` |
| `service/error/CreateCacheCoordinationException.java` | `service/error/CreateCacheCoordinationException.java` |
| `service/error/InvalidRequestException.java` | `service/error/InvalidRequestException.java` |
| `service/error/LinkDisabledException.java` | `service/error/LinkDisabledException.java` |
| `service/error/LinkExpiredException.java` | `service/error/LinkExpiredException.java` |
| `service/error/LinkNotFoundException.java` | `service/error/LinkNotFoundException.java` |
| `service/error/LinkStateConflictException.java` | `service/error/LinkStateConflictException.java` |
| `service/error/ShortCodeGenerationException.java` | `service/error/ShortCodeGenerationException.java` |
| `service/error/StateCacheCoordinationException.java` | `service/error/StateCacheCoordinationException.java` |
| `service/RedirectDecision.java` | `service/RedirectDecision.java` |
| `service/RedirectService.java` | `service/RedirectService.java` |
| `service/ShortLinkCreationService.java` | `service/ShortLinkCreationService.java` |
| `service/ShortLinkStateService.java` | `service/ShortLinkStateService.java` |
| `shortcode/PermutedShortCodeEncoder.java` | `shortcode/PermutedShortCodeEncoder.java` |
| `shortcode/ShortCodeIdIssuer.java` | `shortcode/ShortCodeIdIssuer.java` |
| `stats/AsyncVisitRecorder.java` | `stats/messaging/AsyncVisitRecorder.java` |
| `stats/MySqlVisitRecorder.java` | `stats/persistence/MySqlVisitPersistence.java` |
| `stats/MySqlVisitStatsQuery.java` | `stats/query/MySqlVisitStatsQuery.java` |
| `stats/StatsDataSourceConfiguration.java` | `stats/config/StatsDataSourceConfiguration.java` |
| `stats/StatsDateRange.java` | `stats/StatsDateRange.java` |
| `stats/StatsQueryException.java` | `stats/query/StatsQueryException.java` |
| `stats/VisitCleanupConfiguration.java` | `stats/retention/VisitCleanupConfiguration.java` |
| `stats/VisitCleanupSchedule.java` | `stats/retention/VisitCleanupSchedule.java` |
| `stats/VisitConnectionFactory.java` | `stats/messaging/VisitConnectionFactory.java` |
| `stats/VisitConsumer.java` | `stats/messaging/VisitConsumer.java` |
| `stats/VisitCursor.java` | `stats/query/VisitCursor.java` |
| `stats/VisitEvent.java` | `stats/VisitEvent.java` |
| `stats/VisitIdentity.java` | `stats/collection/VisitIdentity.java` |
| `stats/VisitListenerContainer.java` | `stats/messaging/VisitListenerContainer.java` |
| `stats/VisitLogCleanup.java` | `stats/retention/VisitLogCleanup.java` |
| `stats/VisitMessageCodec.java` | `stats/messaging/VisitMessageCodec.java` |
| `stats/VisitMetadata.java` | `stats/collection/VisitMetadata.java` |
| `stats/VisitPageResult.java` | `stats/query/VisitPageResult.java` |
| `stats/VisitPersistence.java` | `stats/persistence/VisitPersistence.java` |
| `stats/VisitPersistenceException.java` | `stats/persistence/VisitPersistenceException.java` |
| `stats/VisitQueryObservations.java` | `stats/query/VisitQueryObservations.java` |
| `stats/VisitRabbitConfiguration.java` | `stats/messaging/VisitRabbitConfiguration.java` |
| `stats/VisitRabbitProperties.java` | `stats/messaging/VisitRabbitProperties.java` |
| `stats/VisitRecorder.java` | `stats/VisitRecorder.java` |
| `stats/VisitStatsProperties.java` | `stats/config/VisitStatsProperties.java` |
| `stats/VisitStatsResult.java` | `stats/query/VisitStatsResult.java` |
| `stats/VisitWriteObservations.java` | `stats/persistence/VisitWriteObservations.java` |

## 实际包引用

- `根包` → `.cache`
- `.api` → `.api.management`, `.api.stats`, `.service`
- `.api.error` → `.service.error`, `.stats.query`
- `.api.management` → `.api.error`
- `.api.stats` → `.api.management`, `.service`, `.service.error`, `.stats`, `.stats.collection`, `.stats.config`, `.stats.persistence`, `.stats.query`
- `.logging` → `.stats.messaging`
- `.persistence` → `.shortcode`
- `.service` → `.cache`, `.persistence`, `.service.error`, `.shortcode`
- `.stats` → `.service.error`
- `.stats.messaging` → `.stats`, `.stats.collection`, `.stats.persistence`
- `.stats.persistence` → `.stats`, `.stats.config`
- `.stats.query` → `.service.error`, `.stats`, `.stats.config`
- `.stats.retention` → `.stats`, `.stats.config`
