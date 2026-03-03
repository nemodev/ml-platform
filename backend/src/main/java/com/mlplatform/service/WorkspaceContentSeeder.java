package com.mlplatform.service;

import com.mlplatform.config.StorageConfig.MinioProperties;
import io.minio.BucketExistsArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.messages.Item;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

@Service
public class WorkspaceContentSeeder {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceContentSeeder.class);

    private static final List<String> TEMPLATE_FILES = List.of(
            "sample-delta-data.ipynb",
            "batch-inference.ipynb",
            "visualize/sample_dashboard.py"
    );

    private final MinioClient minioClient;
    private final MinioProperties properties;

    public WorkspaceContentSeeder(MinioClient minioClient, MinioProperties properties) {
        this.minioClient = minioClient;
        this.properties = properties;
    }

    public void seedAnalysisWorkspace(String username, UUID analysisId) {
        String bucket = properties.getAnalysisBucket();
        String prefix = buildAnalysisPrefix(username, analysisId);

        try {
            ensureBucketExists(bucket);

            if (prefixHasObjects(bucket, prefix)) {
                log.debug("Analysis workspace already seeded: {}/{}", bucket, prefix);
                return;
            }

            // Create directory marker so s3fs can detect the prefix as a directory
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(prefix)
                    .stream(new ByteArrayInputStream(new byte[0]), 0, -1)
                    .contentType("application/x-directory")
                    .build());

            for (String templateFile : TEMPLATE_FILES) {
                byte[] content = loadTemplate(templateFile);
                String objectKey = prefix + templateFile;
                String contentType = guessContentType(templateFile);

                minioClient.putObject(PutObjectArgs.builder()
                        .bucket(bucket)
                        .object(objectKey)
                        .stream(new ByteArrayInputStream(content), content.length, -1)
                        .contentType(contentType)
                        .build());
                log.debug("Seeded {}/{}", bucket, objectKey);
            }

            log.info("Seeded analysis workspace: {}/{}", bucket, prefix);
        } catch (Exception ex) {
            log.warn("Failed to seed analysis workspace {}/{}: {}", bucket, prefix, ex.getMessage());
        }
    }

    public void deleteAnalysisWorkspace(String username, UUID analysisId) {
        String bucket = properties.getAnalysisBucket();
        String prefix = buildAnalysisPrefix(username, analysisId);

        try {
            Iterable<Result<Item>> objects = minioClient.listObjects(ListObjectsArgs.builder()
                    .bucket(bucket)
                    .prefix(prefix)
                    .recursive(true)
                    .build());

            int count = 0;
            for (Result<Item> result : objects) {
                Item item = result.get();
                minioClient.removeObject(RemoveObjectArgs.builder()
                        .bucket(bucket)
                        .object(item.objectName())
                        .build());
                count++;
            }

            log.info("Deleted {} objects from analysis workspace: {}/{}", count, bucket, prefix);
        } catch (Exception ex) {
            log.warn("Failed to delete analysis workspace {}/{}: {}", bucket, prefix, ex.getMessage());
        }
    }

    private String buildAnalysisPrefix(String username, UUID analysisId) {
        String base = properties.getAnalysisPrefix();
        if (base == null || base.isBlank()) {
            base = "analysis";
        }
        base = base.replaceAll("/+$", "");
        return base + "/" + sanitize(username) + "/" + analysisId + "/";
    }

    private boolean prefixHasObjects(String bucket, String prefix) throws Exception {
        Iterable<Result<Item>> results = minioClient.listObjects(ListObjectsArgs.builder()
                .bucket(bucket)
                .prefix(prefix)
                .maxKeys(1)
                .build());
        return results.iterator().hasNext();
    }

    private void ensureBucketExists(String bucket) throws Exception {
        if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
    }

    private byte[] loadTemplate(String templateFile) throws IOException {
        ClassPathResource resource = new ClassPathResource("workspace-templates/" + templateFile);
        try (InputStream is = resource.getInputStream()) {
            return is.readAllBytes();
        }
    }

    private String guessContentType(String filename) {
        if (filename.endsWith(".ipynb")) {
            return "application/x-ipynb+json";
        } else if (filename.endsWith(".py")) {
            return "text/x-python";
        }
        return "application/octet-stream";
    }

    private String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.replaceAll("[^a-zA-Z0-9._-]", "-");
    }
}
