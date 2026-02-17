package com.mlplatform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AirflowService {

    public record AirflowDagRun(
            String dagRunId,
            String state,
            Instant startDate,
            Instant endDate,
            String note
    ) {}

    private final RestTemplate airflowRestTemplate;
    private final ObjectMapper objectMapper;
    private final Environment environment;

    public AirflowService(
            @Qualifier("airflowRestTemplate") RestTemplate airflowRestTemplate,
            ObjectMapper objectMapper,
            Environment environment
    ) {
        this.airflowRestTemplate = airflowRestTemplate;
        this.objectMapper = objectMapper;
        this.environment = environment;
    }

    public AirflowDagRun triggerDagRun(String dagId, Map<String, Object> conf) {
        if (isDevProfile()) {
            long now = Instant.now().toEpochMilli();
            return new AirflowDagRun("dev-" + now, "queued", null, null, "Waiting for resources");
        }

        // Airflow creates new DAGs in paused state by default; ensure triggers are runnable.
        ensureDagUnpaused(dagId);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("dag_run_id", "portal-" + UUID.randomUUID());
        payload.put("conf", conf == null ? Map.of() : conf);

        JsonNode body = postForNode("/api/v1/dags/{dagId}/dagRuns", payload, dagId);
        String dagRunId = text(body.path("dag_run_id"));
        if (dagRunId == null || dagRunId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Airflow did not return dag_run_id");
        }

        return new AirflowDagRun(
                dagRunId,
                text(body.path("state")),
                parseInstant(text(body.path("start_date"))),
                parseInstant(text(body.path("end_date"))),
                text(body.path("note"))
        );
    }

    public AirflowDagRun getDagRunStatus(String dagId, String runId) {
        if (isDevProfile()) {
            return getDevStatus(runId);
        }

        try {
            String response = airflowRestTemplate.getForObject(
                    "/api/v1/dags/{dagId}/dagRuns/{runId}",
                    String.class,
                    dagId,
                    runId
            );
            JsonNode body = objectMapper.readTree(response);
            return new AirflowDagRun(
                    text(body.path("dag_run_id")),
                    text(body.path("state")),
                    parseInstant(text(body.path("start_date"))),
                    parseInstant(text(body.path("end_date"))),
                    text(body.path("note"))
            );
        } catch (HttpStatusCodeException ex) {
            if (ex.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Pipeline run not found in Airflow");
            }
            throw toResponseStatus(ex);
        } catch (ResourceAccessException ex) {
            throw new AirflowUnavailableException("Pipeline orchestration service is unavailable", ex);
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new AirflowUnavailableException("Pipeline orchestration service is unavailable", ex);
        }
    }

    private JsonNode postForNode(String path, Map<String, Object> payload, String dagId) {
        try {
            String response = airflowRestTemplate.postForObject(path, payload, String.class, dagId);
            return objectMapper.readTree(response);
        } catch (HttpStatusCodeException ex) {
            throw toResponseStatus(ex);
        } catch (ResourceAccessException ex) {
            throw new AirflowUnavailableException("Pipeline orchestration service is unavailable", ex);
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new AirflowUnavailableException("Pipeline orchestration service is unavailable", ex);
        }
    }

    private void ensureDagUnpaused(String dagId) {
        try {
            airflowRestTemplate.patchForObject(
                    "/api/v1/dags/{dagId}",
                    Map.of("is_paused", false),
                    String.class,
                    dagId
            );
        } catch (Exception ex) {
            // If this fails, triggering may still succeed when DAG is already unpaused.
        }
    }

    private AirflowDagRun getDevStatus(String runId) {
        long createdAt = parseDevEpoch(runId);
        long elapsedSeconds = Math.max(0, (Instant.now().toEpochMilli() - createdAt) / 1000);

        if (runId != null && runId.toLowerCase(Locale.ROOT).contains("fail") && elapsedSeconds > 15) {
            return new AirflowDagRun(runId, "failed", Instant.ofEpochMilli(createdAt + 5000), Instant.now(), "Notebook execution failed");
        }
        if (elapsedSeconds < 10) {
            return new AirflowDagRun(runId, "queued", null, null, "Waiting for resources");
        }
        if (elapsedSeconds < 25) {
            return new AirflowDagRun(runId, "running", Instant.ofEpochMilli(createdAt + 5000), null, "Notebook is running");
        }
        return new AirflowDagRun(runId, "success", Instant.ofEpochMilli(createdAt + 5000), Instant.now(), "Notebook completed");
    }

    private long parseDevEpoch(String runId) {
        if (runId == null || !runId.startsWith("dev-")) {
            return Instant.now().minusSeconds(30).toEpochMilli();
        }
        String value = runId.substring(4);
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return Instant.now().minusSeconds(30).toEpochMilli();
        }
    }

    private ResponseStatusException toResponseStatus(HttpStatusCodeException ex) {
        HttpStatusCode statusCode = ex.getStatusCode();
        HttpStatus status = HttpStatus.resolve(statusCode.value());
        if (status == null) {
            status = HttpStatus.BAD_GATEWAY;
        }
        String message = ex.getResponseBodyAsString();
        if (message == null || message.isBlank()) {
            message = "Airflow request failed";
        }
        return new ResponseStatusException(status, message);
    }

    private String text(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asText(null);
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }

    private Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (Exception ex) {
            return null;
        }
    }

    private boolean isDevProfile() {
        return environment.matchesProfiles("dev");
    }
}
