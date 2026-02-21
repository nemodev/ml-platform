package com.mlplatform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
@EnableConfigurationProperties(JupyterHubConfig.JupyterHubProperties.class)
public class JupyterHubConfig {

    @Bean
    public WebClient jupyterHubWebClient(JupyterHubProperties properties) {
        return WebClient.builder()
                .baseUrl(properties.getUrl())
                .build();
    }

    @Bean
    public WebClient jupyterHubProxyWebClient(JupyterHubProperties properties) {
        String proxyUrl = properties.getProxyUrl();
        if (proxyUrl == null || proxyUrl.isBlank()) {
            proxyUrl = properties.getUrl();
        }
        return WebClient.builder()
                .baseUrl(proxyUrl)
                .build();
    }

    @ConfigurationProperties(prefix = "services.jupyterhub")
    public static class JupyterHubProperties {
        private String url;
        private String proxyUrl;
        private String publicUrl;
        private String apiToken;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getPublicUrl() {
            return publicUrl;
        }

        public void setPublicUrl(String publicUrl) {
            this.publicUrl = publicUrl;
        }

        public String getProxyUrl() {
            return proxyUrl;
        }

        public void setProxyUrl(String proxyUrl) {
            this.proxyUrl = proxyUrl;
        }

        public String getApiToken() {
            return apiToken;
        }

        public void setApiToken(String apiToken) {
            this.apiToken = apiToken;
        }
    }
}
