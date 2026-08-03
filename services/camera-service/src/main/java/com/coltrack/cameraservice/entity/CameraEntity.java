package com.coltrack.cameraservice.entity;


import jakarta.persistence.*;

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
            nullable = false
    )
    private Integer cameraNumber;


    @Column(nullable = false)
    private String name;

    private String rtspUrl;

    @Column(nullable = false)
    private Instant createdAt;


    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
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
    @JoinColumn(name = "lbs_id")
    private LbsLocationEntity location;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private CameraCategoryEntity category;

}
