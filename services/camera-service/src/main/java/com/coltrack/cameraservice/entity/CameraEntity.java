package com.coltrack.cameraservice.entity;


import jakarta.persistence.*;
import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.*;

import java.time.Instant;
import java.util.UUID;


@Entity
@Table(
        name = "cameras",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_camera_number",
                        columnNames = "camera_number"
                )
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CameraEntity {

    @Id
    private UUID id;

    @Column(
            name = "camera_number",
            nullable = false,
            unique = true
    )
    private Integer cameraNumber;


    @Column(nullable = false)
    private String name;

    private String rtspUrl;

    @JsonIgnore
    private String rtspUsername;

    @JsonIgnore
    @Column(columnDefinition = "TEXT")
    private String rtspPasswordEncrypted;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private RtspUrlFormat rtspUrlFormat = RtspUrlFormat.STANDARD;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private VideoProcessingMode videoProcessingMode = VideoProcessingMode.AUTO;

    public boolean isCredentialsConfigured() {
        return rtspPasswordEncrypted != null && !rtspPasswordEncrypted.isBlank();
    }

    @Column(nullable = false)
    private Instant createdAt;


    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private CameraStatus status = CameraStatus.OFFLINE;

    private Instant lastHeartbeat;

    @Column(nullable = false)
    @Builder.Default
    private boolean autoStart = false;

    @Column(columnDefinition = "TEXT")
    private String lastError;

    @Column
    private Instant lastStatusChangedAt;


    // LBS
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "lbs_id",
            nullable = true
    )
    private LbsLocationEntity lbsLocation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private CameraCategoryEntity category;

}
