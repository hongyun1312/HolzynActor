# HolzynActor · 远程数据接收通道设计 V1.0

> **文档类型**：预留设计文档（本轮只出设计，不写代码）
> **日期**：2026-08-17
> **定位**：需求 2「远程数据库（预留）」的接收侧设计——发行后用户在本机使用（本地 H2 为主），自愿上报「注册世界时的设备信息、世界观概要」到开发者的远程库，用于二次开发。

---

## 一、背景与目标

- **现状**：数据存储以本地 H2 为主，远程 MySQL 通过 `--spring.profiles.active=remote` 预留为连接侧能力；
- **目标**：建立「本地为主、远程接收为辅」的**单向数据接收通道**——用户在**自愿开启**的前提下，注册世界时上报轻量元数据，开发者据此了解：
  - 版本分布（客户端版本、系统环境、设备指纹）；
  - 使用趋势（新建项目数、题材/世界观分布、规模统计）；
  - 不采集任何隐私/敏感明文（API Key、对话内容、角色档案全文）。

## 二、总体架构

```
┌─ 用户本机（exe/Web）──────────────────────────────┐
│  本地 H2（主）  ──►  上报模块（可选开关，默认关）    │
└───────────────┬────────────────────────────────────┘
                │ HTTPS（定时/事件触发，幂等重试）
┌───────────────▼────────────────────────────────────┐
│  开发者远程接收服务（预留，可复用本模块 backend）      │
│  POST /telemetry/registration       设备+项目注册    │
│  POST /telemetry/heartbeat          心跳（可选）     │
│  → 远程 MySQL（actor_telemetry_* 表）               │
└────────────────────────────────────────────────────┘
```

## 三、配置与开关（预留配置项）

```yaml
holzyn:
  actor:
    remote:
      enabled: false            # 总开关（默认关闭；前端设置页「远程同步」开关联动）
      base-url: ""              # 接收服务地址（如 https://actor.holzyn.com/telemetry）
      api-token: ""             # 可选鉴权令牌（匿名亦可，防刷由服务端限流）
      report-world-on-create: true   # 是否在「注册世界（新建/导入项目）」时上报
      report-device-on-first-run: true # 是否在首次启动时上报设备信息
```

## 四、上报数据契约

### 4.1 POST /telemetry/registration（注册/建世界时上报）

```json
{
  "schemaVersion": "1",
  "clientId": "uuid（首次生成，本地持久化，用于去重）",
  "appVersion": "1.0.0",
  "platform": { "os": "windows", "arch": "x64", "locale": "zh-CN" },
  "device": { "cpuCores": 8, "ramGB": 32, "gpu": "NVIDIA GeForce RTX 5080" },
  "eventType": "world_created",                 // world_created / world_imported
  "project": {
    "uid": "projectUid（去重）",
    "nameHash": "sha256(名称)（可选：名称脱敏指纹）",
    "genre": "奇幻",
    "era": "现代",
    "characterCount": 6,
    "crowdCount": 2
  }
}
```

### 4.2 数据边界（绝不包含）

- ❌ API Key / 供应商密钥（明文或密文均不上报）；
- ❌ 对话消息内容、记忆全文、角色卡全文、知识库全文；
- ❌ 本地账户个人档案（昵称/头像/签名/身份/喜好/禁忌/个人档案）；
- ❌ 任何文件路径、绝对路径、用户名。

## 五、远程库表设计（预留）

```sql
-- 接收侧（开发者远程库），表前缀 actor_telemetry_
CREATE TABLE actor_telemetry_client (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  client_id VARCHAR(36) UNIQUE NOT NULL,        -- 客户端指纹
  first_seen_at DATETIME NOT NULL,
  last_seen_at DATETIME NOT NULL,
  app_version VARCHAR(20),
  os VARCHAR(20), arch VARCHAR(20), locale VARCHAR(20),
  cpu_cores INT, ram_gb INT, gpu VARCHAR(100),
  report_count INT NOT NULL DEFAULT 0
);
CREATE TABLE actor_telemetry_world (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  client_id VARCHAR(36) NOT NULL,
  project_uid VARCHAR(36) NOT NULL,             -- 去重（同一项目只计一次）
  event_type VARCHAR(20) NOT NULL,
  genre VARCHAR(50), era VARCHAR(50),
  character_count INT, crowd_count INT,
  created_at DATETIME NOT NULL,
  UNIQUE KEY uk_telemetry_world (project_uid)
);
```

## 六、实现要点（后续阶段）

1. **前端**：设置 → 通用设置 →「远程同步」开关（默认关，需用户主动开启）；
2. **后端**：`domain/telemetry`（预留功能域）——上报 Service + 定时/事件触发器 + 幂等去重（clientId + projectUid）+ 失败退避重试（指数退避，最多 5 次）+ 离线队列（本地表暂存，联网补报）；
3. **合规**：首次开启时明确告知采集范围（仅元数据）；遵守最小化原则；提供一键关闭并清除本地暂存；
4. **服务端**：可用本模块 backend 的 `remote` profile + 独立 `telemetry` 控制面，或复用现网 web 服务（新增端点），限流 + 匿名鉴权令牌。

## 七、分期

| 阶段 | 内容 |
|---|---|
| A（本轮） | 设计文档 + 配置项预留（不写代码） |
| B | 后端 `domain/telemetry` 上报实现 + 远程建表脚本 + 前端设置开关 |
| C | 接收服务上线 + 数据分析看板 |
