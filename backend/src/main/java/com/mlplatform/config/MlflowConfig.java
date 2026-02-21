package com.mlplatform.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestTemplate;

@Configuration
@EnableConfigurationProperties(MlflowConfig.MlflowProperties.class)
public class MlflowConfig {

    @Bean
    public RestTemplate mlflowRestTemplate(RestTemplateBuilder builder, MlflowProperties properties) {
        return builder
                .rootUri(stripTrailingSlash(properties.getUrl()))
                .setConnectTimeout(Duration.ofSeconds(5))
                .setReadTimeout(Duration.ofSeconds(60))
                .additionalInterceptors((request, body, execution) -> {
                    request.getHeaders().setAccept(List.of(MediaType.APPLICATION_JSON));
                    request.getHeaders().set("X-ML-Platform-Proxy", "backend");
                    return execution.execute(request, body);
                })
                .build();
    }

    private String stripTrailingSlash(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("/+$", "");
    }

    @ConfigurationProperties(prefix = "services.mlflow")
    public static class MlflowProperties {
        private String url;
        private String trackingUrl;
        private String artifactDestination;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getTrackingUrl() {
            return trackingUrl;
        }

        public void setTrackingUrl(String trackingUrl) {
            this.trackingUrl = trackingUrl;
        }

        public String getArtifactDestination() {
            return artifactDestination;
        }

        public void setArtifactDestination(String artifactDestination) {
            this.artifactDestination = artifactDestination;
        }
    }
}
