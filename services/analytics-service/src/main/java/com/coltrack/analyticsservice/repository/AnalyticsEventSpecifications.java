package com.coltrack.analyticsservice.repository;

import com.coltrack.analyticsservice.entity.AnalyticsEventEntity;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;

public final class AnalyticsEventSpecifications {

    private AnalyticsEventSpecifications() {
    }

    public static Specification<AnalyticsEventEntity> withFilters(
            String cameraId,
            String eventType,
            String objectType,
            OffsetDateTime from,
            OffsetDateTime to
    ) {
        return Specification.allOf(
                equalIfPresent("cameraId", cameraId),
                equalIfPresent("eventType", eventType),
                equalIfPresent("objectType", objectType),
                occurredAtFrom(from),
                occurredAtTo(to)
        );
    }

    private static Specification<AnalyticsEventEntity> equalIfPresent(
            String attribute,
            String value
    ) {
        return StringUtils.hasText(value)
                ? (root, query, builder) -> builder.equal(root.get(attribute), value.trim())
                : null;
    }

    private static Specification<AnalyticsEventEntity> occurredAtFrom(OffsetDateTime from) {
        return from == null
                ? null
                : (root, query, builder) ->
                builder.greaterThanOrEqualTo(
                        root.<OffsetDateTime>get("occurredAt"), from
                );
    }

    private static Specification<AnalyticsEventEntity> occurredAtTo(OffsetDateTime to) {
        return to == null
                ? null
                : (root, query, builder) ->
                builder.lessThanOrEqualTo(
                        root.<OffsetDateTime>get("occurredAt"), to
                );
    }
}
