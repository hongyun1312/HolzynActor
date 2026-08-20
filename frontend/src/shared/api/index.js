// ============================================================
// 前端 API 聚合入口（共享层）
// 按功能域拆分到各模块：account / project / world / character /
// conversation / crowd / knowledge / memory / settings / misc。
// 本文件统一 re-export，保持「从 @/shared/api 导入」的既有写法兼容。
// ============================================================
export { default as http } from './http'

// 本地账户
export * from './account'
// 项目
export * from './project'
// 世界（时钟/时间线/场景/演化）
export * from './world'
// 角色（NPC/角色卡/行动）
export * from './character'
// 对话（单聊/群聊/世界事件）
export * from './conversation'
// 人群
export * from './crowd'
// 知识库
export * from './knowledge'
// 记忆
export * from './memory'
// 设置（模型 API/Prompt 模板/用量）
export * from './settings'
// 通用（健康/通知）
export * from './misc'
// 新建项目工作流（文件解析 / 世界初始化，SSE）
export * from './workflow'
