package com.smartenergy.sensor.service;

import com.smartenergy.contracts.common.PageResponse;
import com.smartenergy.contracts.error.ApiException;
import com.smartenergy.contracts.error.ErrorCode;
import com.smartenergy.contracts.sensor.SensorRequest;
import com.smartenergy.contracts.sensor.SensorResponse;
import com.smartenergy.sensor.model.SensorDocument;
import com.smartenergy.sensor.repository.SensorRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/** CRUD des capteurs (EF-05). Suppression = desactivation logique. */
@Service
public class SensorService {

    private final SensorRepository repository;
    private final MongoTemplate mongo;

    public SensorService(SensorRepository repository, MongoTemplate mongo) {
        this.repository = repository;
        this.mongo = mongo;
    }

    public SensorResponse create(SensorRequest request) {
        if (repository.existsBySensorId(request.sensorId())) {
            throw alreadyExists(request.sensorId());
        }
        Instant now = Instant.now();
        SensorDocument doc = new SensorDocument(request.sensorId());
        apply(doc, request);
        doc.setActive(request.active() == null || request.active());
        doc.setCreatedAt(now);
        doc.setUpdatedAt(now);
        try {
            return toResponse(repository.save(doc));
        } catch (DuplicateKeyException e) { // course entre deux creations simultanees (index unique)
            throw alreadyExists(request.sensorId());
        }
    }

    public SensorResponse get(String sensorId) {
        return toResponse(find(sensorId));
    }

    public PageResponse<SensorResponse> list(int page, int size, String building, Boolean active) {
        if (page < 0) throw ApiException.of(ErrorCode.INVALID_PARAMETER, "page doit etre superieur ou egal a 0");
        if (size < 1 || size > 100) throw ApiException.of(ErrorCode.INVALID_PARAMETER, "size doit etre compris entre 1 et 100");

        Query filter = new Query();
        if (building != null && !building.isBlank()) filter.addCriteria(Criteria.where("building").is(building));
        if (active != null) filter.addCriteria(Criteria.where("active").is(active));

        long total = mongo.count(Query.of(filter).limit(-1).skip(-1), SensorDocument.class);
        List<SensorResponse> items = mongo
                .find(Query.of(filter).with(PageRequest.of(page, size, Sort.by("sensorId"))), SensorDocument.class)
                .stream().map(SensorService::toResponse).toList();
        return new PageResponse<>(items, page, size, total);
    }

    public SensorResponse update(String sensorId, SensorRequest request) {
        if (!sensorId.equals(request.sensorId())) {
            throw ApiException.of(ErrorCode.INVALID_PARAMETER, "sensorId du corps different de celui de l'URL");
        }
        SensorDocument doc = find(sensorId);
        if (request.version() != null && !request.version().equals(doc.getVersion())) {
            throw ApiException.of(ErrorCode.VERSION_CONFLICT,
                    "Version " + request.version() + " perimee (courante : " + doc.getVersion() + ")");
        }
        apply(doc, request);
        if (request.active() != null) doc.setActive(request.active());
        doc.setUpdatedAt(Instant.now());
        return toResponse(repository.save(doc)); // OptimisticLockingFailureException -> 409 (platform-web)
    }

    public void deactivate(String sensorId) {
        SensorDocument doc = find(sensorId);
        doc.setActive(false);
        doc.setUpdatedAt(Instant.now());
        repository.save(doc);
    }

    // ------------------------------------------------------------------ utilitaires

    private SensorDocument find(String sensorId) {
        return repository.findBySensorId(sensorId).orElseThrow(
                () -> ApiException.of(ErrorCode.SENSOR_NOT_FOUND, "Capteur introuvable : " + sensorId));
    }

    private static void apply(SensorDocument doc, SensorRequest r) {
        doc.setName(r.name());
        doc.setBuilding(r.building());
        doc.setFloor(r.floor());
        doc.setMaxPowerKw(r.maxPowerKw());
        doc.setTags(r.tags() == null ? List.of() : List.copyOf(r.tags()));
    }

    private static ApiException alreadyExists(String sensorId) {
        return ApiException.of(ErrorCode.SENSOR_ALREADY_EXISTS, "Le capteur existe deja : " + sensorId);
    }

    static SensorResponse toResponse(SensorDocument d) {
        return new SensorResponse(d.getSensorId(), d.getName(), d.getBuilding(), d.getFloor(), d.getMaxPowerKw(),
                d.isActive(), d.getTags() == null ? List.of() : d.getTags(),
                d.getVersion() == null ? 0L : d.getVersion(), d.getCreatedAt(), d.getUpdatedAt());
    }
}
