CREATE TABLE IF NOT EXISTS capability_registry (
  capability_name VARCHAR(128) PRIMARY KEY,
  short_summary VARCHAR(500) NOT NULL DEFAULT '',
  aliases TEXT NOT NULL DEFAULT '[]',
  keywords TEXT NOT NULL DEFAULT '[]',
  domain_tags TEXT NOT NULL DEFAULT '[]',
  priority INT NOT NULL DEFAULT 0,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  updated_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS skill_capability_binding (
  skill_name VARCHAR(128) NOT NULL,
  capability_name VARCHAR(128) NOT NULL,
  priority INT NOT NULL DEFAULT 0,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  updated_at TIMESTAMP NOT NULL DEFAULT now(),
  PRIMARY KEY (skill_name, capability_name),
  CONSTRAINT fk_skill_capability_skill FOREIGN KEY (skill_name) REFERENCES skill_index(name),
  CONSTRAINT fk_skill_capability_capability FOREIGN KEY (capability_name) REFERENCES capability_registry(capability_name)
);

CREATE INDEX IF NOT EXISTS idx_capability_registry_active
  ON capability_registry(active, priority DESC);
CREATE INDEX IF NOT EXISTS idx_skill_capability_binding_capability
  ON skill_capability_binding(capability_name, active, priority DESC);
