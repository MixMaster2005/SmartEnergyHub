package com.smartenergy.sensor.model;

import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Collection sensor_db.sensors (CDC section 6.2). Index unique sur sensorId cree par scripts/init-mongo.js.
 * {@code version} : verrou optimiste (un ecrasement concurrent leve OptimisticLockingFailureException -> 409).
 */
@Document("sensors")
public class SensorDocument {

    @Id
    private String id;
    private String sensorId;
    private String name;
    private String building;
    private Integer floor;
    private double maxPowerKw;
    private boolean active;
    private List<String> tags;
    @Version
    private Long version;
    private Instant createdAt;
    private Instant updatedAt;

    protected SensorDocument() {}

    public SensorDocument(String sensorId) { this.sensorId = sensorId; }

    public String getId() { return id; }
    public String getSensorId() { return sensorId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getBuilding() { return building; }
    public void setBuilding(String building) { this.building = building; }
    public Integer getFloor() { return floor; }
    public void setFloor(Integer floor) { this.floor = floor; }
    public double getMaxPowerKw() { return maxPowerKw; }
    public void setMaxPowerKw(double maxPowerKw) { this.maxPowerKw = maxPowerKw; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }
    public Long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
