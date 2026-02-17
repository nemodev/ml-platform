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
@EnableConfigurationProperties(AirflowConfig.AirflowProperties.class)
public class AirflowConfig {

    @Bean
    public RestTemplate airflowRestTemplate(RestTemplateBuilder builder, AirflowProperties properties) {
        return builder
                .rootUri(stripTrailingSlash(properties.getUrl()))
                .setConnectTimeout(Duration.ofSeconds(5))
                .setReadTimeout(Duration.ofSeconds(60))
                .basicAuthentication(properties.getUsername(), properties.getPassword())
                .additionalInterceptors((request, body, execution) -> {
                    request.getHeaders().setAccept(List.of(MediaType.APPLICATION_JSON));
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

    @ConfigurationProperties(prefix = "services.airflow")
    public static class AirflowProperties {
        private String url;
        private String username;
        private String password;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }
}
