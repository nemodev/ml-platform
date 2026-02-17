package com.mlplatform.config;

import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.util.ClientBuilder;
import io.kubernetes.client.util.KubeConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@org.springframework.context.annotation.Configuration
@EnableConfigurationProperties(KServeConfig.KServeProperties.class)
public class KServeConfig {

    @Bean
    public ApiClient kubernetesApiClient(KServeProperties properties) throws IOException {
        ApiClient apiClient;
        if (properties.getKubeconfigPath() != null && !properties.getKubeconfigPath().isBlank()) {
            Path kubeconfigPath = Path.of(properties.getKubeconfigPath());
            try (var reader = Files.newBufferedReader(kubeconfigPath)) {
                KubeConfig kubeConfig = KubeConfig.loadKubeConfig(reader);
                if (properties.getKubeContext() != null && !properties.getKubeContext().isBlank()) {
                    kubeConfig.setContext(properties.getKubeContext());
                }
                apiClient = ClientBuilder.kubeconfig(kubeConfig).build();
            }
        } else {
            apiClient = ClientBuilder.standard().build();
        }

        apiClient.setReadTimeout((int) Duration.ofSeconds(30).toMillis());
        apiClient.setDebugging(false);
        io.kubernetes.client.openapi.Configuration.setDefaultApiClient(apiClient);
        return apiClient;
    }

    @ConfigurationProperties(prefix = "services.kserve")
    public static class KServeProperties {
        private String namespace = "ml-platform-serving";
        private String serviceAccountName = "kserve-s3-sa";
        private String modelFormatName = "mlflow";
        private String runtimeName = "kserve-mlserver";
        private String kubeconfigPath;
        private String kubeContext;
        private boolean mockEnabled;

        public String getNamespace() {
            return namespace;
        }

        public void setNamespace(String namespace) {
            this.namespace = namespace;
        }

        public String getServiceAccountName() {
            return serviceAccountName;
        }

        public void setServiceAccountName(String serviceAccountName) {
            this.serviceAccountName = serviceAccountName;
        }

        public String getModelFormatName() {
            return modelFormatName;
        }

        public void setModelFormatName(String modelFormatName) {
            this.modelFormatName = modelFormatName;
        }

        public String getRuntimeName() {
            return runtimeName;
        }

        public void setRuntimeName(String runtimeName) {
            this.runtimeName = runtimeName;
        }

        public String getKubeconfigPath() {
            return kubeconfigPath;
        }

        public void setKubeconfigPath(String kubeconfigPath) {
            this.kubeconfigPath = kubeconfigPath;
        }

        public String getKubeContext() {
            return kubeContext;
        }

        public void setKubeContext(String kubeContext) {
            this.kubeContext = kubeContext;
        }

        public boolean isMockEnabled() {
            return mockEnabled;
        }

        public void setMockEnabled(boolean mockEnabled) {
            this.mockEnabled = mockEnabled;
        }
    }
}
