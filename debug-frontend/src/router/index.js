import { createRouter, createWebHashHistory } from 'vue-router'

/**
 * 调试前端路由（hash 模式，2026-08-20）。
 * 使用 hash 路由（#/overview 等）：构建产物部署在 /debug/ 子路径，
 * 页面内跳转全部走 hash，后端只需转发 /debug 与 /debug/ 到 index.html，
 * 不产生任何后端路由冲突（设计文档 §4.2 / §7 风险 5）。
 */
const routes = [
  { path: '/', redirect: '/overview' },
  { path: '/overview', name: 'overview', component: () => import('../views/OverviewView.vue'), meta: { title: '总览' } },
  { path: '/runtime', name: 'runtime', component: () => import('../views/RuntimeOverviewView.vue'), meta: { title: 'AgentRuntime 概览' } },
  { path: '/logs', name: 'logs', component: () => import('../views/LogsView.vue'), meta: { title: '实时日志' } },
  { path: '/ai', name: 'ai', component: () => import('../views/AiTraceView.vue'), meta: { title: 'AI Trace' } },
  // D5（F19 决策链路可视化）：AI 调用详情 JSON 结构化渲染页（2026-08-27）
  { path: '/ai-call-detail', name: 'ai-call-detail', component: () => import('../views/AiCallDetailView.vue'), meta: { title: 'AI 调用详情' } },
  // D5（R4）：漂移度量视图（2026-08-27）
  { path: '/drift', name: 'drift', component: () => import('../views/DriftMetricsView.vue'), meta: { title: '漂移度量' } },
  // M5（记忆系统检索注入与巩固收尾）：记忆注入预览页（2026-09-14，分场景注入组合 §9 可视化）
  { path: '/injection', name: 'injection', component: () => import('../views/InjectionPreviewView.vue'), meta: { title: '记忆注入预览' } },
  // M4（NPC 状态模块）：状态曲线页 + 调参面板（2026-09-03，历史快照曲线/运行时调参）
  { path: '/state', name: 'state', component: () => import('../views/StateView.vue'), meta: { title: '状态曲线' } },
  { path: '/state-tuning', name: 'state-tuning', component: () => import('../views/NpcStateTuningView.vue'), meta: { title: '状态调参' } },
  // M5（实时行为决策系统）：刺激日志页（2026-09-08，刺激时间线 + 护栏命中统计可视化验收）
  { path: '/stimulus', name: 'stimulus', component: () => import('../views/StimulusLogView.vue'), meta: { title: '刺激日志（M5）' } },
  // 记忆系统重构 M2/M3（2026-09-12）：项目记忆页（编年史 L2 + 实体状态 L3 + 概览）
  { path: '/world-memory', name: 'world-memory', component: () => import('../views/WorldMemoryView.vue'), meta: { title: '项目记忆' } },
  // 宏观社会演化 M3/M4/M5（2026-09-16）：宏观面板页（状态摘要/指标曲线/事件/入账流水/模板与 AI 强度控制）
  { path: '/macro', name: 'macro', component: () => import('../views/MacroView.vue'), meta: { title: '宏观面板' } },
  // O1 记忆内核扩展（2026-09-16，O1a+b+c）：记忆模糊/谱系/路由三视图（艾宾浩斯 L1 模糊 + 睡眠清洗 +
  // L1→L2→L3 蒸馏谱系反向链 + 档位化注入路由预览与 trace 回放）
  { path: '/mem-o1', name: 'mem-o1', component: () => import('../views/MemoryO1View.vue'), meta: { title: '记忆内核 O1' } },
  // O2 决策与协调（2026-09-16，P1-1/P1-2/P1-3）：前瞻推演记录与触发预览、state delta 回看、
  // 冲突检测与协商记录、资源占用登记、ToM 档位预览
  { path: '/decision-coord', name: 'decision-coord', component: () => import('../views/DecisionCoordView.vue'), meta: { title: '决策协调 O2' } },
  // O2.5 行为质量评估（2026-09-19，P2-1 决策样本评分报告 + 专门测试接口五能力）：
  // ① 阶段控制 ② 状态聚合 ③ 干预驱动 ④ 评估批跑（LLM-as-Judge） ⑤ test_run_log 完整回放
  { path: '/eval-test', name: 'eval-test', component: () => import('../views/EvaluationTestView.vue'), meta: { title: '行为评估 O2.5' } },
  // O3 评估与工程（2026-09-21，P2-4 统一护栏 + P2-5 场景-模型路由）：路由生效表+运行时覆盖 /
  // 护栏状态（开关/每场景统计/最近告警）+ 运行时覆盖
  { path: '/ai-policy', name: 'ai-policy', component: () => import('../views/AiPolicyView.vue'), meta: { title: '护栏与路由 O3' } },
  // O4 体验与架构（2026-09-21）：Prompt 工程页（分层上下文重构/模板规格/格式合规率/前缀缓存/judge 一致性）
  { path: '/prompt-eng', name: 'prompt-eng', component: () => import('../views/PromptEngView.vue'), meta: { title: 'Prompt 工程 O4' } },
  // O6 轨道 G2（2026-09-21）：生命与人格页（全生命周期阶段机 + 此刻可发生空间 + 里程碑 +
  // 阶段门控表热更新 + G2 指标；测试操作受 holzyn.actor.evaluation.test-enabled 门控）
  { path: '/life', name: 'life', component: () => import('../views/LifeView.vue'), meta: { title: '生命与人格 O6' } },
  // U1 服务端对接层（2026-09-21）：Unity 对接页（WS 模拟客户端[连接/上行发送/下行事件流回放] +
  // REST init/status + 时钟/感知/环境快捷动作）
  { path: '/unity-bridge', name: 'unity-bridge', component: () => import('../views/UnityBridgeView.vue'), meta: { title: 'Unity 对接 U1' } },
  // N 轨道 N3/N4（2026-09-24/25）：NanoJev 判定层页（逐场景开关[测试操作切换] / 判定统计 /
  // 最近判定记录[分布·top1Prob·熵·采纳/升级/回退]；space_decision 场景自动入表）
  { path: '/nanojev-decision', name: 'nanojev-decision', component: () => import('../views/NanoJevDecisionView.vue'), meta: { title: 'NanoJev 判定 N3/N4' } },
  // N 轨道 N5（2026-09-26）：认知 LOD + 高频微决策页（注意力卡 / 微决策卡 / 重算触发卡三卡观测）
  { path: '/n5-cognition', name: 'n5-cognition', component: () => import('../views/N5CognitionView.vue'), meta: { title: '认知 LOD + 微决策 N5' } },
  { path: '/usage', name: 'usage', component: () => import('../views/UsageView.vue'), meta: { title: '用量图' } },
  { path: '/db', name: 'db', component: () => import('../views/DbBrowserView.vue'), meta: { title: 'DB 浏览' } },
  { path: '/events', name: 'events', component: () => import('../views/EventsView.vue'), meta: { title: '事件流' } },
  { path: '/config', name: 'config', component: () => import('../views/ConfigView.vue'), meta: { title: '配置' } },
  { path: '/:pathMatch(.*)*', redirect: '/overview' }
]

const router = createRouter({
  history: createWebHashHistory(),
  routes
})

router.afterEach((to) => {
  if (to.meta?.title) {
    document.title = `${to.meta.title} · HolzynActor 调试控制台`
  }
})

export default router
