package com.smartenergy.platform.web;

import com.smartenergy.contracts.common.ApiPaths;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** GET /api/health : route de sante simple (etape 0 du guide). L'etat detaille est dans /actuator/health. */
@RestController
public class HealthController {

    private final String serviceName;

    public HealthController(String serviceName) {
        this.serviceName = serviceName;
    }

    @GetMapping(ApiPaths.HEALTH)
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("service", serviceName);
        body.put("time", Instant.now().toString());
        return body;
    }
}
