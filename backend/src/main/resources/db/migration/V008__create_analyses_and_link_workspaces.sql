-- Create analyses table: first-class entity for grouping notebooks + MLflow experiments
CREATE TABLE IF NOT EXISTS analyses (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    name VARCHAR(255) NOT NULL,
    description TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_analyses_user_id ON analyses(user_id);
CREATE UNIQUE INDEX IF NOT EXISTS ux_analyses_user_id_name ON analyses(user_id, name);

-- Link workspaces to analyses (NULLable for backward compatibility with existing rows)
ALTER TABLE workspaces ADD COLUMN analysis_id UUID REFERENCES analyses(id);
CREATE INDEX IF NOT EXISTS idx_workspaces_analysis_id ON workspaces(analysis_id);

-- Enforce one active workspace per analysis (replaces per-user constraint)
CREATE UNIQUE INDEX IF NOT EXISTS ux_workspaces_analysis_active
    ON workspaces(analysis_id) WHERE status IN ('PENDING', 'RUNNING', 'IDLE');
