-- ============================================================
-- HolzynActor（NPC 角色 AI 驱动模块）建表脚本 · H2 嵌入式（MySQL 兼容模式）
-- 目标：本地数据库（主）——H2 文件库 jdbc:h2:file:./data/holzyn-actor;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE
-- 说明：
--   1) 与 MySQL 版（V1.0__holzyn_actor_mysql_all_tables.sql）保持同一套业务表；
--   2) 类型适配：LONGTEXT→CLOB、TEXT→CLOB、JSON→JSON、DATETIME(3)→TIMESTAMP(3)、
--      省略 ENGINE/CHARSET/ON UPDATE CURRENT_TIMESTAMP（H2 兼容）；
--   3) 实际运行时 JPA ddl-auto=update 会自动建表/补列，本脚本作为手动初始化与结构参考；
--   4) sys_user 为 web 共享表，本地单用户模式不依赖（CurrentUserProvider 恒返回 id=1）。
-- ============================================================

-- ---------- 1. 项目/世界观 ----------
CREATE TABLE IF NOT EXISTS actor_project (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  name VARCHAR(100) NOT NULL,
  code VARCHAR(50) DEFAULT NULL,
  cover_url VARCHAR(255) DEFAULT NULL,
  summary CLOB,
  status TINYINT NOT NULL DEFAULT 0,
  deleted TINYINT NOT NULL DEFAULT 0,
  project_uid VARCHAR(36) DEFAULT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_actor_project_uid UNIQUE (project_uid)
);
CREATE INDEX IF NOT EXISTS idx_project_user ON actor_project(user_id);
CREATE INDEX IF NOT EXISTS idx_project_status ON actor_project(status);

CREATE TABLE IF NOT EXISTS actor_world_setting (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  project_id BIGINT NOT NULL,
  version INT NOT NULL DEFAULT 1,
  name VARCHAR(100) DEFAULT NULL,
  genre VARCHAR(50) DEFAULT NULL,
  era VARCHAR(50) DEFAULT NULL,
  geography CLOB,
  factions CLOB,
  magic_system CLOB,
  culture CLOB,
  history CLOB,
  free_text CLOB,
  status TINYINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_world_project ON actor_world_setting(project_id);

-- ---------- 1.5 世界观地点表（V1.9 新增：AI 从地理设定提取 + 手动维护，项目级） ----------
CREATE TABLE IF NOT EXISTS actor_world_location (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  project_id BIGINT NOT NULL,
  name VARCHAR(100) NOT NULL,
  type VARCHAR(50) DEFAULT NULL,
  intro CLOB,
  importance INT NOT NULL DEFAULT 3,
  sort_order INT NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_world_location_project ON actor_world_location(project_id);

-- ---------- 2. 角色 ----------
CREATE TABLE IF NOT EXISTS actor_character (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  project_id BIGINT NOT NULL,
  type VARCHAR(10) NOT NULL DEFAULT 'special',
  name VARCHAR(50) NOT NULL,
  title VARCHAR(50) DEFAULT NULL,
  detail CLOB,
  avatar_url VARCHAR(255) DEFAULT NULL,
  is_protagonist TINYINT NOT NULL DEFAULT 0,
  importance TINYINT NOT NULL DEFAULT 1,
  status TINYINT NOT NULL DEFAULT 0,
  deleted TINYINT NOT NULL DEFAULT 0,
  current_activity VARCHAR(255) DEFAULT NULL,
  location VARCHAR(255) DEFAULT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_character_project ON actor_character(project_id);
CREATE INDEX IF NOT EXISTS idx_character_type ON actor_character(type);

CREATE TABLE IF NOT EXISTS actor_character_card (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  character_id BIGINT NOT NULL,
  version INT NOT NULL DEFAULT 1,
  persona_json JSON,
  system_prompt CLOB,
  source VARCHAR(20) NOT NULL DEFAULT 'generated',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_card_character ON actor_character_card(character_id);

CREATE TABLE IF NOT EXISTS actor_character_relation (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  project_id BIGINT NOT NULL,
  from_character_id BIGINT NOT NULL,
  to_character_id BIGINT NOT NULL,
  relation_type VARCHAR(50) NOT NULL,
  description VARCHAR(255) DEFAULT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_relation_from ON actor_character_relation(from_character_id);
CREATE INDEX IF NOT EXISTS idx_relation_to ON actor_character_relation(to_character_id);
CREATE INDEX IF NOT EXISTS idx_relation_project ON actor_character_relation(project_id);

-- ---------- 3. 对话 ----------
CREATE TABLE IF NOT EXISTS actor_conversation (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  project_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  mode VARCHAR(10) NOT NULL DEFAULT 'single',
  title VARCHAR(100) DEFAULT NULL,
  world_event_enabled TINYINT NOT NULL DEFAULT 0,
  last_message_at TIMESTAMP DEFAULT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_conversation_project ON actor_conversation(project_id);
CREATE INDEX IF NOT EXISTS idx_conversation_user ON actor_conversation(user_id);

CREATE TABLE IF NOT EXISTS actor_conversation_member (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  conversation_id BIGINT NOT NULL,
  character_id BIGINT NOT NULL,
  join_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_member_conversation ON actor_conversation_member(conversation_id);
CREATE INDEX IF NOT EXISTS idx_member_character ON actor_conversation_member(character_id);

CREATE TABLE IF NOT EXISTS actor_message (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  conversation_id BIGINT NOT NULL,
  character_id BIGINT DEFAULT NULL,
  role VARCHAR(10) NOT NULL,
  type VARCHAR(10) NOT NULL DEFAULT 'text',
  content CLOB,
  raw_stream CLOB,
  status VARCHAR(10) NOT NULL DEFAULT 'done',
  token_in INT NOT NULL DEFAULT 0,
  token_out INT NOT NULL DEFAULT 0,
  prompt_cache_hit_tokens INT NOT NULL DEFAULT 0,
  prompt_cache_miss_tokens INT NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_message_conversation ON actor_message(conversation_id);
CREATE INDEX IF NOT EXISTS idx_message_character ON actor_message(character_id);

-- ---------- 4. 行动 ----------
CREATE TABLE IF NOT EXISTS actor_action_plan (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  character_id BIGINT NOT NULL,
  conversation_id BIGINT DEFAULT NULL,
  action_json JSON,
  trigger_type VARCHAR(20) NOT NULL DEFAULT 'manual',
  status VARCHAR(20) NOT NULL DEFAULT 'planned',
  planned_time TIMESTAMP DEFAULT NULL,
  executed_at TIMESTAMP DEFAULT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_action_character ON actor_action_plan(character_id);
CREATE INDEX IF NOT EXISTS idx_action_status ON actor_action_plan(status);

CREATE TABLE IF NOT EXISTS actor_action_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  character_id BIGINT NOT NULL,
  plan_id BIGINT DEFAULT NULL,
  summary VARCHAR(255) NOT NULL,
  detail CLOB,
  log_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_log_character ON actor_action_log(character_id);
CREATE INDEX IF NOT EXISTS idx_log_time ON actor_action_log(log_time);

-- ---------- 5. 普通型 NPC ----------
CREATE TABLE IF NOT EXISTS actor_crowd (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  project_id BIGINT NOT NULL,
  name VARCHAR(100) NOT NULL,
  config_json JSON,
  member_count INT NOT NULL DEFAULT 0,
  status TINYINT NOT NULL DEFAULT 0,
  enabled TINYINT NOT NULL DEFAULT 1,
  last_schedule_at TIMESTAMP(3) DEFAULT NULL,
  latest_summary CLOB,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_crowd_project ON actor_crowd(project_id);

CREATE TABLE IF NOT EXISTS actor_crowd_member (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  crowd_id BIGINT NOT NULL,
  name VARCHAR(50) DEFAULT NULL,
  profile_json JSON,
  state VARCHAR(20) NOT NULL DEFAULT 'idle',
  last_action VARCHAR(255) DEFAULT NULL,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_crowd_member_group ON actor_crowd_member(crowd_id);
CREATE INDEX IF NOT EXISTS idx_crowd_member_state ON actor_crowd_member(state);

-- ---------- 6. 知识/记忆 ----------
CREATE TABLE IF NOT EXISTS actor_knowledge_doc (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  project_id BIGINT NOT NULL,
  character_id BIGINT DEFAULT NULL,
  title VARCHAR(100) NOT NULL,
  content CLOB,
  embedding JSON,
  status TINYINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_knowledge_project ON actor_knowledge_doc(project_id);
CREATE INDEX IF NOT EXISTS idx_knowledge_character ON actor_knowledge_doc(character_id);

CREATE TABLE IF NOT EXISTS actor_memory (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  project_id BIGINT DEFAULT NULL,
  character_id BIGINT DEFAULT NULL,
  kind VARCHAR(20) NOT NULL DEFAULT 'fact',
  content CLOB NOT NULL,
  importance TINYINT NOT NULL DEFAULT 1,
  deleted TINYINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_memory_character ON actor_memory(character_id);
CREATE INDEX IF NOT EXISTS idx_memory_project ON actor_memory(project_id);

-- ---------- 7. 平台（AI 供应商/模板/用量） ----------
CREATE TABLE IF NOT EXISTS actor_model_provider (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL DEFAULT 1,
  project_id BIGINT DEFAULT NULL,
  name VARCHAR(50) NOT NULL,
  base_url VARCHAR(255) NOT NULL,
  api_key_cipher CLOB,
  model VARCHAR(100) DEFAULT NULL,
  is_default TINYINT NOT NULL DEFAULT 0,
  supports_stream TINYINT NOT NULL DEFAULT 1,
  priority INT NOT NULL DEFAULT 0,
  enabled TINYINT NOT NULL DEFAULT 1,
  remark VARCHAR(255) DEFAULT NULL,
  embedding_enabled TINYINT NOT NULL DEFAULT 0,
  embedding_model VARCHAR(100) DEFAULT NULL,
  purpose VARCHAR(20) DEFAULT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_provider_enabled ON actor_model_provider(enabled);
CREATE INDEX IF NOT EXISTS idx_provider_user ON actor_model_provider(user_id);
CREATE INDEX IF NOT EXISTS idx_provider_project ON actor_model_provider(project_id);

CREATE TABLE IF NOT EXISTS actor_prompt_template (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL DEFAULT 0,
  project_id BIGINT DEFAULT NULL,
  code VARCHAR(50) NOT NULL,
  name VARCHAR(100) NOT NULL,
  template CLOB NOT NULL,
  version INT NOT NULL DEFAULT 1,
  enabled TINYINT NOT NULL DEFAULT 1,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_template_user_code UNIQUE (user_id, code),
  CONSTRAINT uk_template_user_project_code UNIQUE (user_id, project_id, code)
);

CREATE TABLE IF NOT EXISTS actor_usage_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  project_id BIGINT DEFAULT NULL,
  character_id BIGINT DEFAULT NULL,
  provider_id BIGINT DEFAULT NULL,
  model VARCHAR(100) DEFAULT NULL,
  scene VARCHAR(20) NOT NULL,
  token_in INT NOT NULL DEFAULT 0,
  token_out INT NOT NULL DEFAULT 0,
  prompt_cache_hit_tokens INT NOT NULL DEFAULT 0,
  prompt_cache_miss_tokens INT NOT NULL DEFAULT 0,
  duration_ms INT NOT NULL DEFAULT 0,
  cost DECIMAL(10,4) DEFAULT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_usage_user ON actor_usage_log(user_id);
CREATE INDEX IF NOT EXISTS idx_usage_scene ON actor_usage_log(scene);
CREATE INDEX IF NOT EXISTS idx_usage_created ON actor_usage_log(created_at);
CREATE INDEX IF NOT EXISTS idx_usage_project ON actor_usage_log(project_id);

-- ---------- 8. 世界（时钟/事件/场景/演化） ----------
CREATE TABLE IF NOT EXISTS actor_world_clock (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  project_id BIGINT NOT NULL,
  rate INT NOT NULL DEFAULT 24,
  world_start_at TIMESTAMP DEFAULT NULL,
  world_start_game_hour BIGINT NOT NULL DEFAULT 0,
  last_sim_time TIMESTAMP DEFAULT NULL,
  last_game_hour BIGINT NOT NULL DEFAULT 0,
  paused TINYINT NOT NULL DEFAULT 0,
  paused_at TIMESTAMP(3) DEFAULT NULL,
  last_summary VARCHAR(500) DEFAULT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_world_clock_project UNIQUE (project_id)
);
CREATE INDEX IF NOT EXISTS idx_world_clock_project ON actor_world_clock(project_id);

CREATE TABLE IF NOT EXISTS actor_event (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  project_id BIGINT NOT NULL,
  character_id BIGINT DEFAULT NULL,
  title VARCHAR(200) DEFAULT NULL,
  content CLOB,
  kind VARCHAR(20) NOT NULL,
  source VARCHAR(20) NOT NULL DEFAULT 'manual',
  scene_id BIGINT DEFAULT NULL,
  evolution_id BIGINT DEFAULT NULL,
  game_hour BIGINT DEFAULT NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);
CREATE INDEX IF NOT EXISTS idx_event_project ON actor_event(project_id);
CREATE INDEX IF NOT EXISTS idx_event_character ON actor_event(character_id);
CREATE INDEX IF NOT EXISTS idx_event_source ON actor_event(source);
CREATE INDEX IF NOT EXISTS idx_event_evolution ON actor_event(evolution_id);

CREATE TABLE IF NOT EXISTS actor_scene (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  project_id BIGINT NOT NULL,
  name VARCHAR(100) NOT NULL,
  location VARCHAR(200) DEFAULT NULL,
  description VARCHAR(500) DEFAULT NULL,
  background CLOB,
  source CLOB,
  enabled TINYINT NOT NULL DEFAULT 1,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);
CREATE INDEX IF NOT EXISTS idx_scene_project ON actor_scene(project_id);

CREATE TABLE IF NOT EXISTS actor_evolution (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  project_id BIGINT NOT NULL,
  scene_id BIGINT DEFAULT NULL,
  title VARCHAR(200) DEFAULT NULL,
  mode VARCHAR(10) NOT NULL DEFAULT 'manual',
  status VARCHAR(10) NOT NULL DEFAULT 'running',
  background CLOB,
  turn_count INT NOT NULL DEFAULT 0,
  event_id BIGINT DEFAULT NULL,
  ai_summary CLOB,
  finished_at TIMESTAMP(3) DEFAULT NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);
CREATE INDEX IF NOT EXISTS idx_evolution_project ON actor_evolution(project_id);
CREATE INDEX IF NOT EXISTS idx_evolution_status ON actor_evolution(status);

CREATE TABLE IF NOT EXISTS actor_evolution_participant (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  evolution_id BIGINT NOT NULL,
  character_id BIGINT NOT NULL,
  status VARCHAR(10) NOT NULL DEFAULT 'active',
  join_at TIMESTAMP(3) DEFAULT NULL,
  leave_at TIMESTAMP(3) DEFAULT NULL,
  CONSTRAINT uk_ev_participant UNIQUE (evolution_id, character_id)
);
CREATE INDEX IF NOT EXISTS idx_ev_participant_character ON actor_evolution_participant(character_id);

CREATE TABLE IF NOT EXISTS actor_evolution_turn (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  evolution_id BIGINT NOT NULL,
  character_id BIGINT DEFAULT NULL,
  role VARCHAR(10) NOT NULL DEFAULT 'assistant',
  type VARCHAR(20) NOT NULL DEFAULT 'text',
  content CLOB NOT NULL,
  game_hour BIGINT DEFAULT NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);
CREATE INDEX IF NOT EXISTS idx_ev_turn_evolution ON actor_evolution_turn(evolution_id);
CREATE INDEX IF NOT EXISTS idx_ev_turn_character ON actor_evolution_turn(character_id);

CREATE TABLE IF NOT EXISTS actor_group_chat_config (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  max_replies INT NOT NULL DEFAULT 5,
  updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  CONSTRAINT uk_gcc_user UNIQUE (user_id)
);

-- ---------- 9. 本地个人账户 ----------
CREATE TABLE IF NOT EXISTS actor_local_account (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL DEFAULT 1,
  nickname VARCHAR(64) DEFAULT NULL,
  avatar_url VARCHAR(512) DEFAULT NULL,
  signature VARCHAR(255) DEFAULT NULL,
  identity VARCHAR(255) DEFAULT NULL,
  occupation VARCHAR(255) DEFAULT NULL,
  hobbies VARCHAR(512) DEFAULT NULL,
  taboos VARCHAR(512) DEFAULT NULL,
  profile_text CLOB,
  onboarded TINYINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_local_account_user UNIQUE (user_id)
);

-- ============================================================
-- 结束
-- ============================================================
