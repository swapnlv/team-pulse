CREATE TABLE projects (
    project_id   UUID PRIMARY KEY,
    project_name   VARCHAR(255) NOT NULL
);


CREATE TABLE tasks (
    task_id      UUID PRIMARY KEY,
    title    VARCHAR(255) NOT NULL,
    description  TEXT,
    notes       TEXT,
    start_date    DATE,
    end_date      DATE,
    status    VARCHAR(50) CHECK (status IN ('NOT_STARTED', 'IN_PROGRESS', 'COMPLETED')) NOT NULL,
    project_id   UUID REFERENCES projects(project_id) NOT NULL
);

CREATE INDEX idx_tasks_project_id ON tasks (project_id);