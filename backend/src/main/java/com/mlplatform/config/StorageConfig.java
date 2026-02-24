package com.mlplatform.config;

import io.minio.MinioClient;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(StorageConfig.MinioProperties.class)
public class StorageConfig {

    @Bean
    public MinioClient minioClient(MinioProperties properties) {
        return MinioClient.builder()
                .endpoint(stripTrailingSlash(properties.getEndpoint()))
                .credentials(properties.getAccessKey(), properties.getSecretKey())
                .build();
    }

    private String stripTrailingSlash(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("/+$", "");
    }

    @ConfigurationProperties(prefix = "services.minio")
    public static class MinioProperties {
        private String endpoint;
        private String accessKey;
        private String secretKey;
        private String pipelinesBucket;
        private String pipelinesPrefix = "ml-platform/pipelines";
        private int presignExpirySeconds = 900;

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getAccessKey() {
            return accessKey;
        }

        public void setAccessKey(String accessKey) {
            this.accessKey = accessKey;
        }

        public String getSecretKey() {
            return secretKey;
        }

        public void setSecretKey(String secretKey) {
            this.secretKey = secretKey;
        }

        public String getPipelinesBucket() {
            return pipelinesBucket;
        }

        public void setPipelinesBucket(String pipelinesBucket) {
            this.pipelinesBucket = pipelinesBucket;
        }

        public String getPipelinesPrefix() {
            return pipelinesPrefix;
        }

        public void setPipelinesPrefix(String pipelinesPrefix) {
            this.pipelinesPrefix = pipelinesPrefix;
        }

        public int getPresignExpirySeconds() {
            return presignExpirySeconds;
        }

        public void setPresignExpirySeconds(int presignExpirySeconds) {
            this.presignExpirySeconds = presignExpirySeconds;
        }
    }
}
