<template>
  <div class="project-api-settings">
    <!-- 项目级/用户级 API 配置说明（双模式） -->
    <el-alert v-if="projectId" type="success" :closable="false" show-icon class="api-alert"
      title="API 配置为项目级独立（随 .holzyn 包导入导出，导出可选携带敏感数据）。"
      description="本页同时展示用户级默认 API（来源列标记「用户级」）：运行时「项目级优先、用户级回退」——未配置项目级时自动使用用户级默认 API。项目级行可新增/设为默认；用户级行可编辑/测试/删除（影响所有项目），其默认由全局设置管理。API Key 加密存储，列表仅显示脱敏尾 4 位。" />
    <el-alert v-else type="success" :closable="false" show-icon class="api-alert"
      title="API 配置为「用户级默认」（全局，首页/未进入项目时配置）。"
      description="此处配置的 API 是所有项目的用户级回退默认（新建/解析文件建项目、对话等都需要可用 API），所有项目共享；项目级 API 在对应项目空间 → 设置 → API 配置 中单独配置（项目级优先）。API Key 加密存储，列表仅显示脱敏尾 4 位。" />
    <!-- 复用现有模型 API 配置组件（传 projectId=null 即用户级） -->
    <ModelApiSettings :project-id="projectId" />
  </div>
</template>

<script setup>
/**
 * 设置-API 配置（前端布局重构 V1.0，设计文档 §3.10.2；后端项目化改造 V2.0；双模式改造 V1.2）。
 * <p>职责：项目/全局双模式 AI 模型 API 配置页——列表/新增/编辑/删除/设为默认/连通性测试。
 * 项目模式：归属当前项目（projectId），项目级优先、用户级回退，随 .holzyn 包导入导出；
 * 全局模式（首页顶栏「设置」→ API 配置）：projectId=null，即「用户级默认」（所有项目共享回退）。</p>
 * <p>数据来源：/api/model-apis?projectId=xxx 系列接口（不传=用户级）。</p>
 */
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import ModelApiSettings from '@/features/settings/views/ModelApiSettings.vue'

const route = useRoute()
// 当前项目 ID：项目空间内存在；全局设置（首页 /settings）为 null（= 用户级默认）
const projectId = computed(() => (route.params.id ? Number(route.params.id) : null))
</script>

<style scoped>
.project-api-settings { }
.api-alert { margin-bottom: 14px; }
</style>
