CREATE TABLE IF NOT EXISTS model_deployments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    model_name VARCHAR(255) NOT NULL,
    model_version INTEGER NOT NULL,
    endpoint_name VARCHAR(255) NOT NULL UNIQUE,
    status VARCHAR(20) NOT NULL DEFAULT 'DEPLOYING',
    inference_url VARCHAR(512),
    storage_uri VARCHAR(512) NOT NULL,
    error_message TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    ready_at TIMESTAMP,
    deleted_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_model_deployments_user_id
    ON model_deployments(user_id);

CREATE INDEX IF NOT EXISTS idx_model_deployments_endpoint_name
    ON model_deployments(endpoint_name);

CREATE INDEX IF NOT EXISTS idx_model_deployments_status
    ON model_deployments(status);
