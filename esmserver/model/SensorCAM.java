package io.github.rladmstj.esmserver.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "SensorCAM")
public class SensorCAM {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "detection_id")
    private Long detectionId;

    @ManyToOne
    @JoinColumn(name = "sensor_id", nullable = false)
    private SensorBinding sensor;

    @Column(name = "sensor_time", nullable = false)
    private LocalDateTime sensorTime;

    @Column(name = "bbox_x")
    private Integer bboxX;

    @Column(name = "bbox_y")
    private Integer bboxY;

    @Column(name = "bbox_w")
    private Integer bboxW;

    @Column(name = "bbox_h")
    private Integer bboxH;

    @Column(name = "bbox_conf_score")
    private Float bboxConfScore;

    @Column(name = "state_label", length = 30, nullable = false)
    private String stateLabel;
    
    @Column(name = "state_confidence", nullable = false)
    private Float stateConfidence;

    @Column(name = "mean_depth_m")
    private Float meanDepthM;

    @Column(name = "min_depth_m")
    private Float minDepthM;

    @Column(name = "max_depth_m")
    private Float maxDepthM;

    @Column(name = "center_depth_m")
    private Float centerDepthM;

    @Column(name = "distance_m")
    private Float distanceM;

    @Column(name = "area_px")
    private Integer areaPx;

    @Column(name = "processing_latency_ms")
    private Integer processingLatencyMs;

    @Column(name = "model_version", length = 50)
    private String modelVersion;

    public Long getDetectionId() {
        return detectionId;
    }

    public void setDetectionId(Long detectionId) {
        this.detectionId = detectionId;
    }

    public SensorBinding getSensor() {
        return sensor;
    }

    public void setSensor(SensorBinding sensor) {
        this.sensor = sensor;
    }

    public LocalDateTime getSensorTime() {
        return sensorTime;
    }

    public void setSensorTime(LocalDateTime sensorTime) {
        this.sensorTime = sensorTime;
    }

    public Integer getBboxX() {
        return bboxX;
    }

    public void setBboxX(Integer bboxX) {
        this.bboxX = bboxX;
    }

    public Integer getBboxY() {
        return bboxY;
    }

    public void setBboxY(Integer bboxY) {
        this.bboxY = bboxY;
    }

    public Integer getBboxW() {
        return bboxW;
    }

    public void setBboxW(Integer bboxW) {
        this.bboxW = bboxW;
    }

    public Integer getBboxH() {
        return bboxH;
    }

    public void setBboxH(Integer bboxH) {
        this.bboxH = bboxH;
    }

    public Float getBboxConfScore() {
        return bboxConfScore;
    }

    public void setBboxConfScore(Float bboxConfScore) {
        this.bboxConfScore = bboxConfScore;
    }

    public String getStateLabel() {
        return stateLabel;
    }

    public void setStateLabel(String stateLabel) {
        this.stateLabel = stateLabel;
    }

    public Float getStateConfidence() {
        return stateConfidence;
    }

    public void setStateConfidence(Float stateConfidence) {
        this.stateConfidence = stateConfidence;
    }

    public Float getMeanDepthM() {
        return meanDepthM;
    }

    public void setMeanDepthM(Float meanDepthM) {
        this.meanDepthM = meanDepthM;
    }

    public Float getMinDepthM() {
        return minDepthM;
    }

    public void setMinDepthM(Float minDepthM) {
        this.minDepthM = minDepthM;
    }

    public Float getMaxDepthM() {
        return maxDepthM;
    }

    public void setMaxDepthM(Float maxDepthM) {
        this.maxDepthM = maxDepthM;
    }

    public Float getCenterDepthM() {
        return centerDepthM;
    }

    public void setCenterDepthM(Float centerDepthM) {
        this.centerDepthM = centerDepthM;
    }

    public Float getDistanceM() {
        return distanceM;
    }

    public void setDistanceM(Float distanceM) {
        this.distanceM = distanceM;
    }

    public Integer getAreaPx() {
        return areaPx;
    }

    public void setAreaPx(Integer areaPx) {
        this.areaPx = areaPx;
    }

    public Integer getProcessingLatencyMs() {
        return processingLatencyMs;
    }

    public void setProcessingLatencyMs(Integer processingLatencyMs) {
        this.processingLatencyMs = processingLatencyMs;
    }

    public String getModelVersion() {
        return modelVersion;
    }

    public void setModelVersion(String modelVersion) {
        this.modelVersion = modelVersion;
    }
}
