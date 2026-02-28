package com.mlplatform.service;

import com.mlplatform.config.NotebookImageConfig.NotebookImageProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class ContainerRegistryService {

    private static final Logger log = LoggerFactory.getLogger(ContainerRegistryService.class);

    private final NotebookImageProperties properties;
    private final RestTemplate restTemplate;

    public ContainerRegistryService(NotebookImageProperties properties) {
        this.properties = properties;
        this.restTemplate = new RestTemplate();
    }

    public boolean isExternalRegistry() {
        return "external".equalsIgnoreCase(properties.getRegistry().getType());
    }

    @PostConstruct
    void init() {
        String endpoint = properties.getRegistry().getEndpoint();
        String type = properties.getRegistry().getType();
        log.info("Container registry configured: type={}, endpoint={}", type, endpoint);

        try {
            String protocol = properties.getRegistry().isInsecure() ? "http" : "https";
            String url = protocol + "://" + endpoint + "/v2/";

            if (isExternalRegistry() && hasCredentials()) {
                // Use basic auth for external registry health check
                var headers = new org.springframework.http.HttpHeaders();
                headers.setBasicAuth(
                        properties.getRegistry().getUsername(),
                        properties.getRegistry().getPassword()
                );
                var request = new org.springframework.http.HttpEntity<>(headers);
                restTemplate.exchange(url, org.springframework.http.HttpMethod.GET, request, String.class);
            } else {
                restTemplate.getForEntity(url, String.class);
            }
            log.info("Registry health check passed: {}", endpoint);
        } catch (Exception e) {
            log.warn("Registry health check failed ({}): {}. Builds may fail until registry is available.",
                    endpoint, e.getMessage());
        }
    }

    public boolean hasCredentials() {
        String username = properties.getRegistry().getUsername();
        String password = properties.getRegistry().getPassword();
        return username != null && !username.isBlank() && password != null && !password.isBlank();
    }

    public String getRegistryEndpoint() {
        return properties.getRegistry().getEndpoint();
    }

    public String getImageReference(String imageName, String tag) {
        return properties.getRegistry().getEndpoint() + "/" + imageName + ":" + tag;
    }

    public String getBaseImageReference(String pythonVersion) {
        String baseRegistry = properties.getBaseImageRegistry();
        if (baseRegistry != null && !baseRegistry.isBlank()) {
            return baseRegistry + "/" + properties.getBaseImagePrefix() + ":python-" + pythonVersion;
        }
        return properties.getRegistry().getEndpoint() + "/"
                + properties.getBaseImagePrefix() + ":python-" + pythonVersion;
    }

    public boolean isInsecure() {
        return properties.getRegistry().isInsecure();
    }

    public void deleteImage(String imageName, String tag) {
        String endpoint = properties.getRegistry().getEndpoint();
        String protocol = properties.getRegistry().isInsecure() ? "http" : "https";
        String manifestUrl = protocol + "://" + endpoint + "/v2/" + imageName + "/manifests/" + tag;

        try {
            // Get the digest first
            var headers = new org.springframework.http.HttpHeaders();
            headers.set("Accept", "application/vnd.docker.distribution.manifest.v2+json");
            var request = new org.springframework.http.HttpEntity<>(headers);
            var response = restTemplate.exchange(manifestUrl,
                    org.springframework.http.HttpMethod.GET, request, String.class);
            String digest = response.getHeaders().getFirst("Docker-Content-Digest");

            if (digest != null) {
                String deleteUrl = protocol + "://" + endpoint + "/v2/" + imageName + "/manifests/" + digest;
                restTemplate.delete(deleteUrl);
                log.info("Deleted image from registry: {}:{}", imageName, tag);
            }
        } catch (Exception e) {
            log.warn("Failed to delete image from registry {}:{}: {}", imageName, tag, e.getMessage());
        }
    }
}
