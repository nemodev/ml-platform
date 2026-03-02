package com.mlplatform.config;

import jakarta.annotation.PostConstruct;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@org.springframework.context.annotation.Configuration
@EnableConfigurationProperties(WorkspaceProfileProperties.WorkspaceProfiles.class)
public class WorkspaceProfileProperties {

    @ConfigurationProperties(prefix = "workspace")
    public static class WorkspaceProfiles {

        private List<ProfileConfig> profiles = List.of();

        public List<ProfileConfig> getProfiles() {
            return profiles;
        }

        public void setProfiles(List<ProfileConfig> profiles) {
            this.profiles = profiles;
        }

        public ProfileConfig getById(String id) {
            return profiles.stream()
                    .filter(p -> p.getId().equalsIgnoreCase(id))
                    .findFirst()
                    .orElse(null);
        }

        public ProfileConfig getDefault() {
            return profiles.stream()
                    .filter(ProfileConfig::isDefault)
                    .findFirst()
                    .orElse(profiles.isEmpty() ? null : profiles.get(0));
        }

        @PostConstruct
        void validate() {
            if (profiles.isEmpty()) {
                throw new IllegalStateException("workspace.profiles: at least one profile must be defined");
            }

            Set<String> ids = new HashSet<>();
            int defaultCount = 0;
            for (ProfileConfig profile : profiles) {
                if (profile.getId() == null || profile.getId().isBlank()) {
                    throw new IllegalStateException("workspace.profiles: each profile must have a non-blank id");
                }
                if (!ids.add(profile.getId().toLowerCase())) {
                    throw new IllegalStateException("workspace.profiles: duplicate profile id '" + profile.getId() + "'");
                }
                if (profile.isDefault()) {
                    defaultCount++;
                }
            }
            if (defaultCount != 1) {
                throw new IllegalStateException("workspace.profiles: exactly one profile must be marked as default (found " + defaultCount + ")");
            }
        }
    }

    public static class ProfileConfig {
        private String id;
        private String name;
        private String description;
        private boolean isDefault;
        private String cpuRequest;
        private String cpuLimit;
        private String memoryRequest;
        private String memoryLimit;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public boolean isDefault() {
            return isDefault;
        }

        public void setDefault(boolean isDefault) {
            this.isDefault = isDefault;
        }

        public String getCpuRequest() {
            return cpuRequest;
        }

        public void setCpuRequest(String cpuRequest) {
            this.cpuRequest = cpuRequest;
        }

        public String getCpuLimit() {
            return cpuLimit;
        }

        public void setCpuLimit(String cpuLimit) {
            this.cpuLimit = cpuLimit;
        }

        public String getMemoryRequest() {
            return memoryRequest;
        }

        public void setMemoryRequest(String memoryRequest) {
            this.memoryRequest = memoryRequest;
        }

        public String getMemoryLimit() {
            return memoryLimit;
        }

        public void setMemoryLimit(String memoryLimit) {
            this.memoryLimit = memoryLimit;
        }
    }
}
