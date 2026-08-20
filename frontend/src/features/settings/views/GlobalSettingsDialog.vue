<template>
  <el-dialog v-model="visible" title="设置 · 全局（用户级）" width="900px" top="4vh"
    class="global-settings-dialog" :close-on-click-modal="false" destroy-on-close>
    <!-- 顶部说明（与其他弹窗一致的 el-alert 形态，见画廊导入/导出弹窗） -->
    <el-alert type="info" :closable="false" show-icon class="gs-alert"
      title="全局（用户级）设置，所有项目共享"
      description="此处配置用户级 API / Prompt 默认值，并查看全部项目的 AI 用量总览；项目专属设置（基本信息 / 世界时钟 / 导入导出 / 危险区）请进入对应项目空间 → 设置 中配置。" />

    <!-- 四个设置子页以 Tab 内嵌（与 /settings 全局设置页复用同一套组件，行为完全一致） -->
    <el-tabs v-model="activeTab" class="gs-tabs">
      <el-tab-pane label="通用设置" name="general" lazy>
        <ProjectGeneralSettings embedded />
      </el-tab-pane>
      <el-tab-pane label="API 配置" name="apis" lazy>
        <ModelApiSettings embedded />
      </el-tab-pane>
      <el-tab-pane label="Prompt 模板" name="prompts" lazy>
        <ProjectPromptSettings embedded />
      </el-tab-pane>
      <el-tab-pane label="AI 用量" name="usage" lazy>
        <ProjectUsageSettings embedded />
      </el-tab-pane>
    </el-tabs>

    <template #footer>
      <el-button type="primary" plain @click="visible = false">关闭</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
/**
 * 全局（用户级）设置弹窗 —— 项目空间外所有「设置」入口的统一弹窗（V1.8）。
 * <p>背景：首页「设置」此前为「内嵌 ModelApiSettings 页 + 打开完整设置页路由跳转」，
 * 路由跳转在用户环境不可靠（次对接文档 §13/§14），且弹窗 UI 与其它弹窗不统一；本次重构为
 * 标准 el-dialog（标题 + 说明 + Tab 内容 + 底部按钮），与画廊导入/导出等弹窗形态一致。</p>
 * <p>职责：将全局设置四个子页（通用设置 / API 配置 / Prompt 模板 / AI 用量）以 Tab 形式内嵌，
 * 零路由依赖——通过 expose 的 open(tab) 打开；子页复用 /settings 全局设置页同一套组件
 * （各组件在非项目路由下 projectId 为空 = 用户级默认 / 全部项目总用量），保证行为一致。</p>
 * <p>使用：父组件持有本组件 ref，调用 ref.open('apis') 打开并定位到指定 Tab（默认 apis=API 配置）。</p>
 */
import { ref } from 'vue'
import ProjectGeneralSettings from './ProjectGeneralSettings.vue'
import ModelApiSettings from './ModelApiSettings.vue'
import ProjectPromptSettings from './ProjectPromptSettings.vue'
import ProjectUsageSettings from './ProjectUsageSettings.vue'

const visible = ref(false)
/** 当前激活 Tab：general / apis / prompts / usage */
const activeTab = ref('apis')

/**
 * 打开设置弹窗。
 *
 * @param {string} tab 初始 Tab（general/apis/prompts/usage），默认 apis（新账户最常需要配置 API）
 */
function open(tab = 'apis') {
  activeTab.value = tab
  visible.value = true
}

defineExpose({ open })
</script>

<style scoped>
.gs-alert { margin-bottom: 14px; }
.gs-tabs { --el-tabs-header-margin-bottom: 14px; }
</style>

<!-- 弹窗体：受限最大高度 + 内部滚动，小屏下避免被截断（对话框 teleport 到 body，需非 scoped 规则） -->
<style>
.global-settings-dialog .el-dialog__body { max-height: calc(100vh - 190px); overflow-y: auto; }
.global-settings-dialog .el-dialog__body::-webkit-scrollbar { width: 8px; }
</style>
