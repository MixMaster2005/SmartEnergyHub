package com.smartenergy.sensor.repository;

import com.smartenergy.sensor.model.SensorDocument;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface SensorRepository extends MongoRepository<SensorDocument, String> {
    Optional<SensorDocument> findBySensorId(String sensorId);

    boolean existsBySensorId(String sensorId);
}
