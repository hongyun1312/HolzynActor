-- ============================================================
-- HolzynActor（NPC 角色 AI 驱动模块）优化版建表脚本 · MySQL
-- 目标库：holzyn（actor_ 前缀表，utf8mb4）
-- 依据：远程库 mysql.mbfsr.com:23306/holzyn 实测表结构（2026-08-17 采集）
--       + 历史 V1.0~V2.4 迁移脚本语义
-- 优化点：
--   1) 统一字段类型与默认值（TINYINT→INT 归一、DATETIME 精度统一、JSON 列明确）；
--   2) 补齐缺失索引（actor_event/scene/evolution/evolution_* 原库无索引 → 按外键补 idx_*）；
--   3) 新增 actor_local_account（本地个人账户，需求 3：昵称/头像/签名 + NPC 个性化档案）；
--   4) 与 H2 脚本（H2__holzyn_actor_all_tables.sql）保持同一套业务表结构。
-- 说明：sys_user 为 web 模块共享表，不在此重复建。
-- ============================================================

-- ---------- 1. 项目/世界观 ----------
CREATE TABLE IF NOT EXISTS actor_project (
  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
  user_id BIGINT NOT NULL COMMENT '归属用户 ID（本地单用户=1）',
  name VARCHAR(100) NOT NULL COMMENT '项目名称',
  code VARCHAR(50) DEFAULT NULL COMMENT '项目编码',
  cover_url VARCHAR(255) DEFAULT NULL COMMENT '封面图 URL',
  summary TEXT COMMENT '项目概要',
  status TINYINT NOT NULL DEFAULT 0 COMMENT '状态：0草稿/1已生成角色卡/2进行中',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '软删除标记：0正常/1已删除',
  project_uid VARCHAR(36) DEFAULT NULL COMMENT '项目唯一标识（.holzyn 包导入幂等检测）',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  KEY idx_project_user (user_id),
  KEY idx_project_status (status),
  KEY idx_project_uid (project_uid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='actor 项目（作品）主表';

CREATE TABLE IF NOT EXISTS actor_world_setting (
  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
  project_id BIGINT NOT NULL COMMENT '项目 ID（关联 actor_project.id）',
  version INT NOT NULL DEFAULT 1 COMMENT '版本号（自增）',
  name VARCHAR(100) DEFAULT NULL COMMENT '世界观名称',
  genre VARCHAR(50) DEFAULT NULL COMMENT '题材（奇幻/科幻/都市/历史等）',
  era VARCHAR(50) DEFAULT NULL COMMENT '时代背景',
  geography TEXT COMMENT '地理/地图设定',
  factions TEXT COMMENT '势力/阵营',
  magic_system TEXT COMMENT '规则体系（魔法/科技/规则）',
  culture TEXT COMMENT '文化/风俗',
  history TEXT COMMENT '历史背景',
  free_text LONGTEXT COMMENT '完整世界观自由文本（知识库注入源）',
  status TINYINT NOT NULL DEFAULT 0 COMMENT '状态：0草稿/1生效',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  KEY idx_world_project (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='actor 世界观设定（版本化）';

-- ---------- 1.5 世界观地点表（V1.9 新增：AI 从地理设定提取 + 手动维护，项目级） ----------
CREATE TABLE IF NOT EXISTS actor_world_location (
  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
  project_id BIGINT NOT NULL COMMENT '项目 ID（关联 actor_project.id）',
  name VARCHAR(100) NOT NULL COMMENT '地点名称',
  type VARCHAR(50) DEFAULT NULL COMMENT '地点类型（城市/城镇/酒馆/森林等）',
  intro TEXT COMMENT '详细简介',
  importance INT NOT NULL DEFAULT 3 COMMENT '重要度（1-5）',
  sort_order INT NOT NULL DEFAULT 0 COMMENT '排序（越小越靠前）',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  KEY idx_world_location_project (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='actor 世界观地点表';

-- ---------- 2. 角色 ----------
CREATE TABLE IF NOT EXISTS actor_character (
  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
  project_id BIGINT NOT NULL COMMENT '项目 ID',
  type VARCHAR(10) NOT NULL DEFAULT 'special' COMMENT '类型：special 特殊型/common 普通型',
  name VARCHAR(50) NOT NULL COMMENT '角色姓名',
  title VARCHAR(50) DEFAULT NULL COMMENT '头衔',
  detail LONGTEXT COMMENT '角色详细信息（用户自行输入的完整档案；角色卡生成的知识源）',
  avatar_url VARCHAR(255) DEFAULT NULL COMMENT '头像 URL',
  is_protagonist TINYINT NOT NULL DEFAULT 0 COMMENT '是否主角：0否/1是',
  importance TINYINT NOT NULL DEFAULT 1 COMMENT '重要度（1-5，决定 AI 投入）',
  status TINYINT NOT NULL DEFAULT 0 COMMENT '状态：0草稿/1正常',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '软删除标记',
  current_activity VARCHAR(255) DEFAULT NULL COMMENT '当前行动描述（行动模拟执行更新）',
  location VARCHAR(255) DEFAULT NULL COMMENT '当前位置（行动模拟执行更新）',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  KEY idx_character_project (project_id),
  KEY idx_character_type (type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='actor 角色主表';

CREATE TABLE IF NOT EXISTS actor_character_card (
  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
  character_id BIGINT NOT NULL COMMENT '角色 ID',
  version INT NOT NULL DEFAULT 1 COMMENT '版本号（自增）',
  persona_json JSON COMMENT '结构化角色卡 JSON（见设计文档 §九 Schema）',
  system_prompt TEXT COMMENT '渲染后的对话系统 Prompt',
  source VARCHAR(20) NOT NULL DEFAULT 'generated' COMMENT '来源：generated 生成/manual 手动/edited 编辑',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  KEY idx_card_character (character_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='actor 角色卡（版本化）';

CREATE TABLE IF NOT EXISTS actor_character_relation (
  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
  project_id BIGINT NOT NULL COMMENT '项目 ID',
  from_character_id BIGINT NOT NULL COMMENT '关系发起方角色 ID',
  to_character_id BIGINT NOT NULL COMMENT '关系目标角色 ID',
  relation_type VARCHAR(50) NOT NULL COMMENT '关系类型（亲属/师徒/敌对等）',
  description VARCHAR(255) DEFAULT NULL COMMENT '关系描述',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  KEY idx_relation_from (from_character_id),
  KEY idx_relation_to (to_character_id),
  KEY idx_relation_project (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='actor 角色社会关系图';

-- ---------- 3. 对话 ----------
CREATE TABLE IF NOT EXISTS actor_conversation (
  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
  project_id BIGINT NOT NULL COMMENT '项目 ID',
  user_id BIGINT NOT NULL COMMENT '归属用户 ID',
  mode VARCHAR(10) NOT NULL DEFAULT 'single' COMMENT '模式：single 单聊/group 群聊',
  title VARCHAR(100) DEFAULT NULL COMMENT '会话标题',
  world_event_enabled TINYINT NOT NULL DEFAULT 0 COMMENT '是否启用世界事件注入：0否/1是',
  last_message_at DATETIME DEFAULT NULL COMMENT '最后消息时间',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  KEY idx_conversation_project (project_id),
  KEY idx_conversation_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='actor 会话（单聊/群聊）';

CREATE TABLE IF NOT EXISTS actor_conversation_member (
  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
  conversation_id BIGINT NOT NULL COMMENT '会话 ID',
  character_id BIGINT NOT NULL COMMENT '角色 ID',
  join_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '加入时间',
  KEY idx_member_conversation (conversation_id),
  KEY idx_member_character (character_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='actor 群聊成员表';

CREATE TABLE IF NOT EXISTS actor_message (
  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
  conversation_id BIGINT NOT NULL COMMENT '会话 ID',
  character_id BIGINT DEFAULT NULL COMMENT '角色 ID（assistant 消息）',
  role VARCHAR(10) NOT NULL COMMENT '角色：user/assistant/system',
  type VARCHAR(10) NOT NULL DEFAULT 'text' COMMENT '类型：text 文本/action 行动/event 事件',
  content LONGTEXT COMMENT '正文（assistant 为最终落库文本）',
  raw_stream LONGTEXT COMMENT 'SSE 流式原始增量（调试用，可清理）',
  status VARCHAR(10) NOT NULL DEFAULT 'done' COMMENT 'SSE 状态：streaming/done/failed',
  token_in INT NOT NULL DEFAULT 0 COMMENT '输入 token 数',
  token_out INT NOT NULL DEFAULT 0 COMMENT '输出 token 数',
  prompt_cache_hit_tokens INT NOT NULL DEFAULT 0 COMMENT '缓存命中 token（DeepSeek 计费）',
  prompt_cache_miss_tokens INT NOT NULL DEFAULT 0 COMMENT '缓存未命中 token',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  KEY idx_message_conversation (conversation_id),
  KEY idx_message_character (character_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='actor 消息表';

-- ---------- 4. 行动 ----------
CREATE TABLE IF NOT EXISTS actor_action_plan (
  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
  character_id BIGINT NOT NULL COMMENT '角色 ID',
  conversation_id BIGINT DEFAULT NULL COMMENT '触发会话 ID',
  action_json JSON COMMENT '行动决策 JSON（UE5 契约预留）',
  trigger_type VARCHAR(20) NOT NULL DEFAULT 'manual' COMMENT '触发源：after_dialog/scheduled/event/manual',
  status VARCHAR(20) NOT NULL DEFAULT 'planned' COMMENT '状态：planned/executing/done/cancelled',
  planned_time DATETIME DEFAULT NULL COMMENT '计划执行时间',
  executed_at DATETIME DEFAULT NULL COMMENT '实际执行时间',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  KEY idx_action_character (character_id),
  KEY idx_action_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='actor 行动决策表';

CREATE TABLE IF NOT EXISTS actor_action_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
  character_id BIGINT NOT NULL COMMENT '角色 ID',
  plan_id BIGINT DEFAULT NULL COMMENT '关联行动决策 ID',
  summary VARCHAR(255) NOT NULL COMMENT '行动摘要',
  detail TEXT COMMENT '行动详情',
  log_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '行动发生时间',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  KEY idx_log_character (character_id),
  KEY idx_log_time (log_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='actor 行动执行日志（时间线）';

-- ---------- 5. 普通型 NPC ----------
CREATE TABLE IF NOT EXISTS actor_crowd (
  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
  project_id BIGINT NOT NULL COMMENT '项目 ID',
  name VARCHAR(100) NOT NULL COMMENT '人群组名称',
  config_json JSON COMMENT '批量生成参数（数量/职业分布/密度）',
  member_count INT NOT NULL DEFAULT 0 COMMENT '成员数量',
  status TINYINT NOT NULL DEFAULT 0 COMMENT '状态：0草稿/1已生成',
  enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用自动调度：0否/1是',
  last_schedule_at DATETIME(3) DEFAULT NULL COMMENT '最近一次自动调度时间',
  latest_summary TEXT COMMENT '最近调度摘要',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  KEY idx_crowd_project (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='actor 普通型人群组';

CREATE TABLE IF NOT EXISTS actor_crowd_member (
  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
  crowd_id BIGINT NOT NULL COMMENT '人群组 ID',
  name VARCHAR(50) DEFAULT NULL COMMENT '成员姓名',
  profile_json JSON COMMENT '参数化个体档案（职业/作息/活动圈）',
  state VARCHAR(20) NOT NULL DEFAULT 'idle' COMMENT '状态（走/停/对话/休息）',
  last_action VARCHAR(255) DEFAULT NULL COMMENT '最近行动描述',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  KEY idx_crowd_member_group (crowd_id),
  KEY idx_crowd_member_state (state)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='actor 人群成员（参数化个体）';

-- ---------- 6. 知识/记忆 ----------
CREATE TABLE IF NOT EXISTS actor_knowledge_doc (
  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
  project_id BIGINT NOT NULL COMMENT '项目 ID',
  character_id BIGINT DEFAULT NULL COMMENT '关联角色 ID（可为空=项目级知识）',
  title VARCHAR(100) NOT NULL COMMENT '文档标题',
  content LONGTEXT COMMENT '文档内容',
  embedding JSON DEFAULT NULL COMMENT '向量（RAG 用）',
  status TINYINT NOT NULL DEFAULT 0 COMMENT '状态：0草稿/1生效',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  KEY idx_knowledge_project (project_id),
  KEY idx_knowledge_character (character_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='actor 知识库文档';

CREATE TABLE IF NOT EXISTS actor_memory (
  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
  project_id BIGINT DEFAULT NULL COMMENT '项目级记忆归属（NULL=历史旧数据未归属）',
  character_id BIGINT DEFAULT NULL COMMENT '角色 ID（NULL=项目级记忆；非空=该角色记忆）',
  kind VARCHAR(20) NOT NULL DEFAULT 'fact' COMMENT '类型：summary 摘要/fact 事实',
  content TEXT NOT NULL COMMENT '记忆内容',
  importance TINYINT NOT NULL DEFAULT 1 COMMENT '重要度（1-5，低重要度滚动淘汰）',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '软删除标记',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  KEY idx_memory_character (character_id),
  KEY idx_memory_project (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='actor 长期记忆';

-- ---------- 7. 平台（AI 供应商/模板/用量） ----------
CREATE TABLE IF NOT EXISTS actor_model_provider (
  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
  user_id BIGINT NOT NULL DEFAULT 1 COMMENT '归属用户 ID（1=本地单用户）',
  project_id BIGINT DEFAULT NULL COMMENT '项目级配置归属（NULL=用户级默认）',
  name VARCHAR(50) NOT NULL COMMENT '供应商名称',
  base_url VARCHAR(255) NOT NULL COMMENT 'API Base URL',
  api_key_cipher TEXT COMMENT '加密后的 API Key（AES-256-GCM，密钥环境变量注入）',
  model VARCHAR(100) DEFAULT NULL COMMENT '默认模型名',
  is_default TINYINT NOT NULL DEFAULT 0 COMMENT '是否默认（同归属内互斥）',
  supports_stream TINYINT NOT NULL DEFAULT 1 COMMENT '是否支持流式：0否/1是',
  priority INT NOT NULL DEFAULT 0 COMMENT '路由优先级（越大越优先）',
  enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用：0否/1是',
  remark VARCHAR(255) DEFAULT NULL COMMENT '备注（用户标注用途/供应商说明）',
  embedding_enabled TINYINT NOT NULL DEFAULT 0 COMMENT '是否启用 embedding：0否/1是',
  embedding_model VARCHAR(100) DEFAULT NULL COMMENT 'embedding 模型名',
  purpose VARCHAR(20) DEFAULT NULL COMMENT '用途：chat/embedding 等',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  KEY idx_provider_enabled (enabled),
  KEY idx_provider_user (user_id),
  KEY idx_provider_project (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='actor AI 模型供应商配置';

CREATE TABLE IF NOT EXISTS actor_prompt_template (
  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
  user_id BIGINT NOT NULL DEFAULT 0 COMMENT '归属用户：0=内置模板/>0=用户覆盖',
  project_id BIGINT DEFAULT NULL COMMENT '项目级覆盖（NULL=用户级）',
  code VARCHAR(50) NOT NULL COMMENT '模板编码（character_card_gen/dialog_system/group_orchestrator/world_event）',
  name VARCHAR(100) NOT NULL COMMENT '模板名称',
  template TEXT NOT NULL COMMENT '模板内容（占位符 {{world_setting}} {{character_json}} 等）',
  version INT NOT NULL DEFAULT 1 COMMENT '模板版本',
  enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用：0否/1是',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  UNIQUE KEY uk_template_user_code (user_id, code),
  UNIQUE KEY uk_template_user_project_code (user_id, project_id, code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='actor Prompt 模板';

CREATE TABLE IF NOT EXISTS actor_usage_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
  user_id BIGINT NOT NULL COMMENT '用户 ID',
  project_id BIGINT DEFAULT NULL COMMENT '项目 ID（关联 actor_project.id）',
  character_id BIGINT DEFAULT NULL COMMENT '角色 ID',
  provider_id BIGINT DEFAULT NULL COMMENT '供应商 ID',
  model VARCHAR(100) DEFAULT NULL COMMENT '模型名',
  scene VARCHAR(20) NOT NULL COMMENT '场景：card_gen/dialog/action/crowd/evolution',
  token_in INT NOT NULL DEFAULT 0 COMMENT '输入 token 数',
  token_out INT NOT NULL DEFAULT 0 COMMENT '输出 token 数',
  prompt_cache_hit_tokens INT NOT NULL DEFAULT 0 COMMENT '缓存命中 token',
  prompt_cache_miss_tokens INT NOT NULL DEFAULT 0 COMMENT '缓存未命中 token',
  duration_ms INT NOT NULL DEFAULT 0 COMMENT '调用耗时（毫秒）',
  cost DECIMAL(10,4) DEFAULT NULL COMMENT '成本',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  KEY idx_usage_user (user_id),
  KEY idx_usage_scene (scene),
  KEY idx_usage_created (created_at),
  KEY idx_usage_project (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='actor AI 调用用量日志';

-- ---------- 8. 世界（时钟/事件/场景/演化） ----------
CREATE TABLE IF NOT EXISTS actor_world_clock (
  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
  project_id BIGINT NOT NULL COMMENT '项目 ID',
  rate INT NOT NULL DEFAULT 24 COMMENT '速率：每真实小时推进的游戏小时数（默认 24=1真实小时推进1游戏日）',
  world_start_at DATETIME DEFAULT NULL COMMENT '真实时刻锚点（默认=项目创建时刻）',
  world_start_game_hour BIGINT NOT NULL DEFAULT 0 COMMENT '锚点对应的游戏起始时刻（小时数，自纪元起）',
  last_sim_time DATETIME DEFAULT NULL COMMENT '最近一次模拟推进的真实时刻',
  last_game_hour BIGINT NOT NULL DEFAULT 0 COMMENT '最近推进到的游戏时刻（小时数，自纪元起）',
  paused TINYINT NOT NULL DEFAULT 0 COMMENT '是否暂停：0否/1是',
  paused_at DATETIME(3) DEFAULT NULL COMMENT '暂停时刻（恢复推进时补算）',
  last_summary VARCHAR(500) DEFAULT NULL COMMENT '最近推进摘要（供前端展示）',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  UNIQUE KEY uk_world_clock_project (project_id),
  KEY idx_world_clock_project (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='actor 世界时钟';

CREATE TABLE IF NOT EXISTS actor_event (
  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
  project_id BIGINT NOT NULL COMMENT '项目 ID',
  character_id BIGINT DEFAULT NULL COMMENT '关联角色 ID（可空）',
  title VARCHAR(200) DEFAULT NULL COMMENT '事件标题',
  content TEXT COMMENT '事件内容',
  kind VARCHAR(20) NOT NULL COMMENT '类型：event/world_event 等',
  source VARCHAR(20) NOT NULL DEFAULT 'manual' COMMENT '来源：manual 手动/ai 生成/evolution 演化归档',
  scene_id BIGINT DEFAULT NULL COMMENT '关联场景 ID（演化归档）',
  evolution_id BIGINT DEFAULT NULL COMMENT '关联演化会话 ID',
  game_hour BIGINT DEFAULT NULL COMMENT '事件发生游戏时刻（小时数）',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  KEY idx_event_project (project_id),
  KEY idx_event_character (character_id),
  KEY idx_event_source (source),
  KEY idx_event_evolution (evolution_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='actor 事件（时间线/演化归档）';

CREATE TABLE IF NOT EXISTS actor_scene (
  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
  project_id BIGINT NOT NULL COMMENT '项目 ID',
  name VARCHAR(100) NOT NULL COMMENT '场景名称',
  location VARCHAR(200) DEFAULT NULL COMMENT '场景地点',
  description VARCHAR(500) DEFAULT NULL COMMENT '场景描述',
  background TEXT COMMENT '场景背景设定',
  source TEXT COMMENT '来源依据（AI 生成时记录世界观/角色依据，逻辑自洽）',
  enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用：0否/1是',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  KEY idx_scene_project (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='actor 场景（世界演化地点）';

CREATE TABLE IF NOT EXISTS actor_evolution (
  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
  project_id BIGINT NOT NULL COMMENT '项目 ID',
  scene_id BIGINT DEFAULT NULL COMMENT '关联场景 ID',
  title VARCHAR(200) DEFAULT NULL COMMENT '演化标题',
  mode VARCHAR(10) NOT NULL DEFAULT 'manual' COMMENT '模式：manual 手动/ai AI 模式',
  status VARCHAR(10) NOT NULL DEFAULT 'running' COMMENT '状态：running 进行中/finished 已归档',
  background TEXT COMMENT '演化背景设定',
  turn_count INT NOT NULL DEFAULT 0 COMMENT '已推进轮次',
  event_id BIGINT DEFAULT NULL COMMENT '归档事件 ID（结束后）',
  ai_summary TEXT COMMENT 'AI 归档摘要',
  finished_at DATETIME(3) DEFAULT NULL COMMENT '结束时刻',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  KEY idx_evolution_project (project_id),
  KEY idx_evolution_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='actor 世界演化会话';

CREATE TABLE IF NOT EXISTS actor_evolution_participant (
  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
  evolution_id BIGINT NOT NULL COMMENT '演化会话 ID',
  character_id BIGINT NOT NULL COMMENT '角色 ID',
  status VARCHAR(10) NOT NULL DEFAULT 'active' COMMENT '状态：active 在场/left 退场',
  join_at DATETIME(3) DEFAULT NULL COMMENT '加入时刻',
  leave_at DATETIME(3) DEFAULT NULL COMMENT '退场时刻',
  UNIQUE KEY uk_ev_participant (evolution_id, character_id),
  KEY idx_ev_participant_character (character_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='actor 演化参与者';

CREATE TABLE IF NOT EXISTS actor_evolution_turn (
  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
  evolution_id BIGINT NOT NULL COMMENT '演化会话 ID',
  character_id BIGINT DEFAULT NULL COMMENT '发言角色 ID（系统/旁白为空）',
  role VARCHAR(10) NOT NULL DEFAULT 'assistant' COMMENT '角色：user/assistant/system/narrator',
  type VARCHAR(20) NOT NULL DEFAULT 'text' COMMENT '类型：text/join/leave/finish',
  content TEXT NOT NULL COMMENT '轮次内容',
  game_hour BIGINT DEFAULT NULL COMMENT '轮次游戏时刻',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  KEY idx_ev_turn_evolution (evolution_id),
  KEY idx_ev_turn_character (character_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='actor 演化轮次';

CREATE TABLE IF NOT EXISTS actor_group_chat_config (
  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
  user_id BIGINT NOT NULL COMMENT '归属用户 ID',
  max_replies INT NOT NULL DEFAULT 5 COMMENT '每轮回复上限（1~20）',
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  UNIQUE KEY uk_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='actor 群聊配置（用户级）';

-- ---------- 9. 本地个人账户（需求 3：本地单用户 + NPC 个性化档案） ----------
CREATE TABLE IF NOT EXISTS actor_local_account (
  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
  user_id BIGINT NOT NULL DEFAULT 1 COMMENT '归属用户 ID（本地单用户=1）',
  nickname VARCHAR(64) DEFAULT NULL COMMENT '昵称（显示名，选填）',
  avatar_url VARCHAR(512) DEFAULT NULL COMMENT '头像（本地路径/URL，选填）',
  signature VARCHAR(255) DEFAULT NULL COMMENT '个性签名（选填）',
  identity VARCHAR(255) DEFAULT NULL COMMENT '结构化档案-身份（注入 NPC 上下文，选填）',
  occupation VARCHAR(255) DEFAULT NULL COMMENT '结构化档案-职业（注入 NPC 上下文，选填）',
  hobbies VARCHAR(512) DEFAULT NULL COMMENT '结构化档案-喜好（注入 NPC 上下文，选填）',
  taboos VARCHAR(512) DEFAULT NULL COMMENT '结构化档案-禁忌（NPC 避免冒犯，注入上下文，选填）',
  profile_text LONGTEXT COMMENT '自由长文本「个人档案」（注入 NPC 上下文，选填）',
  onboarded TINYINT NOT NULL DEFAULT 0 COMMENT '是否完成首次设置向导：0 未完成/1 已完成',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  UNIQUE KEY uk_local_account_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='actor 本地个人账户（本地单用户）';

-- ============================================================
-- 结束
-- ============================================================
