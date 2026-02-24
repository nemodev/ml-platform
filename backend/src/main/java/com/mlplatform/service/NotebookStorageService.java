package com.mlplatform.service;

import com.mlplatform.config.StorageConfig.MinioProperties;
import com.mlplatform.dto.PipelineOutputUrlDto;
import io.minio.BucketExistsArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.http.Method;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class NotebookStorageService {

    private final MinioClient minioClient;
    private final MinioProperties properties;

    public NotebookStorageService(MinioClient minioClient, MinioProperties properties) {
        this.minioClient = minioClient;
        this.properties = properties;
    }

    public String copyNotebookToMinIO(String userId, String notebookPath, UUID runId, byte[] notebookBytes) {
        String objectPath = buildObjectPath(userId, runId, "input.ipynb");
        putNotebookObject(objectPath, notebookBytes);
        return toS3Uri(properties.getPipelinesBucket(), objectPath);
    }

    public String buildOutputPath(String userId, UUID runId) {
        String objectPath = buildObjectPath(userId, runId, "output.ipynb");
        return toS3Uri(properties.getPipelinesBucket(), objectPath);
    }

    public PipelineOutputUrlDto generatePresignedUrl(String outputPath) {
        ParsedS3Path parsed = parseS3Path(outputPath);
        ensureObjectExists(parsed.bucket(), parsed.objectPath());

        try {
            String url = minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(parsed.bucket())
                    .object(parsed.objectPath())
                    .expiry(properties.getPresignExpirySeconds())
                    .build());
            Instant expiresAt = Instant.now().plusSeconds(properties.getPresignExpirySeconds());
            return new PipelineOutputUrlDto(url, expiresAt);
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new NotebookStorageUnavailableException("Unable to generate output notebook URL: " + ex.getMessage(), ex);
        }
    }

    private void putNotebookObject(String objectPath, byte[] notebookBytes) {
        try {
            ensureBucketExists();
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(properties.getPipelinesBucket())
                            .object(objectPath)
                            .stream(new ByteArrayInputStream(notebookBytes), notebookBytes.length, -1)
                            .contentType("application/x-ipynb+json")
                            .build()
            );
        } catch (Exception ex) {
            throw new NotebookStorageUnavailableException("Unable to persist notebook to object storage: " + ex.getMessage(), ex);
        }
    }

    private void ensureBucketExists() throws Exception {
        String bucket = properties.getPipelinesBucket();
        boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
        if (!exists) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
    }

    private void ensureObjectExists(String bucket, String objectPath) {
        try {
            minioClient.statObject(StatObjectArgs.builder().bucket(bucket).object(objectPath).build());
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Output notebook not found");
        }
    }

    private ParsedS3Path parseS3Path(String s3Path) {
        if (s3Path == null || !s3Path.startsWith("s3://")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid output path");
        }
        String value = s3Path.substring("s3://".length());
        int separator = value.indexOf('/');
        if (separator <= 0 || separator >= value.length() - 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid output path");
        }
        String bucket = value.substring(0, separator);
        String objectPath = value.substring(separator + 1);
        return new ParsedS3Path(bucket, objectPath);
    }

    private String buildObjectPath(String userId, UUID runId, String filename) {
        String prefix = normalizePathPrefix(properties.getPipelinesPrefix());
        String safeUser = sanitizePathSegment(userId);
        String runPath = safeUser + "/" + runId + "/" + filename;
        if (prefix.isEmpty()) {
            return runPath;
        }
        return prefix + "/" + runPath;
    }

    private String sanitizePathSegment(String value) {
        if (value == null || value.isBlank()) {
            return "unknown-user";
        }
        return value.replaceAll("[^a-zA-Z0-9._-]", "-");
    }

    private String normalizePathPrefix(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.replaceAll("^/+", "").replaceAll("/+$", "");
        return normalized.isBlank() ? "" : normalized;
    }

    private String toS3Uri(String bucket, String objectPath) {
        return "s3://" + bucket + "/" + objectPath;
    }

    private record ParsedS3Path(String bucket, String objectPath) {}
}
