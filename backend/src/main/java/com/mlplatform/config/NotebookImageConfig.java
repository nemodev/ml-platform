package com.mlplatform.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@org.springframework.context.annotation.Configuration
@EnableConfigurationProperties(NotebookImageConfig.NotebookImageProperties.class)
public class NotebookImageConfig {

    @ConfigurationProperties(prefix = "services.notebook-images")
    public static class NotebookImageProperties {

        private RegistryProperties registry = new RegistryProperties();
        private BuildProperties build = new BuildProperties();
        private String baseImagePrefix = "ml-platform/notebook-base";
        private String baseImageRegistry = "";
        private List<String> pythonVersions = List.of("3.10", "3.11", "3.12");

        public RegistryProperties getRegistry() {
            return registry;
        }

        public void setRegistry(RegistryProperties registry) {
            this.registry = registry;
        }

        public BuildProperties getBuild() {
            return build;
        }

        public void setBuild(BuildProperties build) {
            this.build = build;
        }

        public String getBaseImagePrefix() {
            return baseImagePrefix;
        }

        public void setBaseImagePrefix(String baseImagePrefix) {
            this.baseImagePrefix = baseImagePrefix;
        }

        public String getBaseImageRegistry() {
            return baseImageRegistry;
        }

        public void setBaseImageRegistry(String baseImageRegistry) {
            this.baseImageRegistry = baseImageRegistry;
        }

        public List<String> getPythonVersions() {
            return pythonVersions;
        }

        public void setPythonVersions(List<String> pythonVersions) {
            this.pythonVersions = pythonVersions;
        }
    }

    public static class RegistryProperties {
        private String type = "builtin";
        private String endpoint = "registry.ml-platform.svc:5000";
        private String username = "";
        private String password = "";
        private boolean insecure = true;

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
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

        public boolean isInsecure() {
            return insecure;
        }

        public void setInsecure(boolean insecure) {
            this.insecure = insecure;
        }
    }

    public static class BuildProperties {
        private String namespace = "ml-platform";
        private int timeoutMinutes = 60;
        private int maxConcurrentBuilds = 3;
        private String kanikoImage = "gcr.io/kaniko-project/executor:latest";
        private String buildCpuRequest = "1";
        private String buildMemoryRequest = "2Gi";
        private String buildCpuLimit = "2";
        private String buildMemoryLimit = "4Gi";

        public String getNamespace() {
            return namespace;
        }

        public void setNamespace(String namespace) {
            this.namespace = namespace;
        }

        public int getTimeoutMinutes() {
            return timeoutMinutes;
        }

        public void setTimeoutMinutes(int timeoutMinutes) {
            this.timeoutMinutes = timeoutMinutes;
        }

        public int getMaxConcurrentBuilds() {
            return maxConcurrentBuilds;
        }

        public void setMaxConcurrentBuilds(int maxConcurrentBuilds) {
            this.maxConcurrentBuilds = maxConcurrentBuilds;
        }

        public String getKanikoImage() {
            return kanikoImage;
        }

        public void setKanikoImage(String kanikoImage) {
            this.kanikoImage = kanikoImage;
        }

        public String getBuildCpuRequest() {
            return buildCpuRequest;
        }

        public void setBuildCpuRequest(String buildCpuRequest) {
            this.buildCpuRequest = buildCpuRequest;
        }

        public String getBuildMemoryRequest() {
            return buildMemoryRequest;
        }

        public void setBuildMemoryRequest(String buildMemoryRequest) {
            this.buildMemoryRequest = buildMemoryRequest;
        }

        public String getBuildCpuLimit() {
            return buildCpuLimit;
        }

        public void setBuildCpuLimit(String buildCpuLimit) {
            this.buildCpuLimit = buildCpuLimit;
        }

        public String getBuildMemoryLimit() {
            return buildMemoryLimit;
        }

        public void setBuildMemoryLimit(String buildMemoryLimit) {
            this.buildMemoryLimit = buildMemoryLimit;
        }
    }
}
