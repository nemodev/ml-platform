CREATE TABLE IF NOT EXISTS workspaces (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    profile VARCHAR(50) NOT NULL DEFAULT 'EXPLORATORY',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    pod_name VARCHAR(255),
    jupyterhub_username VARCHAR(255) NOT NULL,
    started_at TIMESTAMP,
    last_activity TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_workspaces_user_id ON workspaces(user_id);
CREATE INDEX IF NOT EXISTS idx_workspaces_status ON workspaces(status);
