package com.coltrack.recordingservice.service;

import com.coltrack.recordingservice.dto.RecordingDateProjection;
import com.coltrack.recordingservice.dto.RecordingDateResponse;
import com.coltrack.recordingservice.dto.RecordingResponse;
import com.coltrack.recordingservice.dto.RecordingPageResponse;
import com.coltrack.recordingservice.dto.RecordingStorageStatusResponse;
import com.coltrack.recordingservice.model.RecordingEntity;
import com.coltrack.recordingservice.model.RecordingStatus;
import com.coltrack.recordingservice.model.RecordingStorageType;
import com.coltrack.recordingservice.repository.RecordingObjectRepository;
import com.coltrack.recordingservice.repository.RecordingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Collection;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RecordingQueryService {

    private final RecordingRepository recordingRepository;
    private final RecordingObjectRepository recordingObjectRepository;
    private final RecordingStorageService recordingStorageService;

    @Transactional(readOnly = true)
    public List<RecordingDateResponse> findAvailableDates(
            UUID cameraId
    ) {

        return recordingRepository
                .findAvailableDates(cameraId)
                .stream()
                .map(projection ->
                        new RecordingDateResponse(
                                projection.getRecordingDate(),
                                projection.getRecordingsCount()
                        )
                )
                .toList();
    }

    public List<RecordingResponse> findByDate(
            UUID cameraId,
            LocalDate date
    ) {

        Instant from =
                date.atStartOfDay(ZoneOffset.UTC)
                        .toInstant();

        Instant to =
                date.plusDays(1)
                        .atStartOfDay(ZoneOffset.UTC)
                        .toInstant();

        return recordingRepository
                .findByCameraIdAndStartedAtGreaterThanEqualAndStartedAtLessThanOrderByStartedAtAsc(
                        cameraId,
                        from,
                        to
                )
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public RecordingPageResponse find(
            UUID cameraId,
            Instant from,
            Instant to,
            Collection<RecordingStatus> statuses,
            Boolean protectedFromDeletion,
            int page,
            int size
    ) {

        if (from != null && to != null && !from.isBefore(to)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "from must be earlier than to"
            );
        }

        Specification<RecordingEntity> specification =
                (root, query, builder) -> builder.conjunction();

        if (cameraId != null) {
            specification = specification.and(
                    (root, query, builder) ->
                            builder.equal(root.get("cameraId"), cameraId)
            );
        }

        if (from != null) {
            specification = specification.and(
                    (root, query, builder) ->
                            builder.greaterThanOrEqualTo(
                                    root.<Instant>get("startedAt"),
                                    from
                            )
            );
        }

        if (to != null) {
            specification = specification.and(
                    (root, query, builder) ->
                            builder.lessThan(root.<Instant>get("startedAt"), to)
            );
        }

        if (statuses != null && !statuses.isEmpty()) {
            specification = specification.and(
                    (root, query, builder) -> root.get("status").in(statuses)
            );
        }

        if (protectedFromDeletion != null) {
            specification = specification.and(
                    (root, query, builder) -> builder.equal(
                            root.get("protectedFromDeletion"),
                            protectedFromDeletion
                    )
            );
        }

        Page<RecordingEntity> result = recordingRepository.findAll(
                specification,
                PageRequest.of(
                        page,
                        size,
                        Sort.by(Sort.Direction.DESC, "startedAt")
                )
        );

        return new RecordingPageResponse(
                result.getContent().stream().map(this::toResponse).toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages()
        );
    }

    @Transactional
    public RecordingResponse setProtected(
            UUID recordingId,
            boolean protectedFromDeletion
    ) {

        RecordingEntity recording = recordingRepository.findById(recordingId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Recording not found"
                ));

        recording.setProtectedFromDeletion(protectedFromDeletion);
        recordingRepository.saveAndFlush(recording);
        return toResponse(recording);
    }

    public RecordingStorageStatusResponse getStorageStatus() {

        RecordingStorageService.StorageCapacity capacity =
                recordingStorageService.getCapacity();

        double usedPercent = capacity.totalBytes() == 0
                ? 0
                : capacity.usedBytes() * 100.0 / capacity.totalBytes();

        return new RecordingStorageStatusResponse(
                capacity.totalBytes(),
                capacity.usableBytes(),
                capacity.usedBytes(),
                recordingRepository.sumCatalogedSizeBytes(),
                usedPercent
        );
    }

    private RecordingResponse toResponse(RecordingEntity recording) {

        boolean local = recordingStorageService.hasRecordingFiles(
                recording.getFilePath()
        );
        boolean s3 = recordingObjectRepository.existsByRecordingId(
                recording.getId()
        );

        RecordingStorageType storageType = local && s3
                ? RecordingStorageType.HYBRID
                : local
                ? RecordingStorageType.LOCAL
                : s3
                ? RecordingStorageType.S3
                : RecordingStorageType.MISSING;

        return RecordingResponse.from(recording, storageType);
    }
}
