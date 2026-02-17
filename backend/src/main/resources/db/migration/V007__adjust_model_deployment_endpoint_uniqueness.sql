ALTER TABLE model_deployments
    DROP CONSTRAINT IF EXISTS model_deployments_endpoint_name_key;

DROP INDEX IF EXISTS idx_model_deployments_endpoint_name;

CREATE UNIQUE INDEX IF NOT EXISTS ux_model_deployments_endpoint_name_active
    ON model_deployments(endpoint_name)
    WHERE deleted_at IS NULL;
