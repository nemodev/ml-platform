-- Notebook image definitions
CREATE TABLE notebook_images (
    id              UUID            NOT NULL PRIMARY KEY,
    user_id         UUID            NOT NULL REFERENCES users(id),
    name            VARCHAR(255)    NOT NULL,
    python_version  VARCHAR(10)     NOT NULL,
    packages        TEXT,
    extra_pip_index_url VARCHAR(1024),
    status          VARCHAR(20)     NOT NULL DEFAULT 'PENDING',
    image_reference VARCHAR(512),
    error_message   VARCHAR(1000),
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_notebook_images_user_id ON notebook_images(user_id);
CREATE UNIQUE INDEX uq_notebook_images_user_name ON notebook_images(user_id, name);

-- Image build attempts
CREATE TABLE image_builds (
    id                  UUID            NOT NULL PRIMARY KEY,
    notebook_image_id   UUID            NOT NULL REFERENCES notebook_images(id) ON DELETE CASCADE,
    status              VARCHAR(20)     NOT NULL DEFAULT 'QUEUED',
    progress_stage      VARCHAR(100),
    build_logs          TEXT,
    image_reference     VARCHAR(512),
    error_message       VARCHAR(1000),
    k8s_job_name        VARCHAR(255),
    started_at          TIMESTAMP WITH TIME ZONE,
    completed_at        TIMESTAMP WITH TIME ZONE,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_image_builds_notebook_image_id ON image_builds(notebook_image_id);
CREATE INDEX idx_image_builds_status ON image_builds(status);
