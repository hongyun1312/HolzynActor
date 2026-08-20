<template>
  <div class="settings-page">
    <!-- 全局设置（首页/未进入项目空间）时显示顶部栏，便于返回画廊 -->
    <ConsoleTopbar v-if="!projectId" brand-label="HolzynActor" :show-collapse="false" />
    <div class="settings-view">
      <div class="settings-head">
        <div class="settings-title">设置</div>
        <div class="settings-desc" v-if="projectId">
          项目设置中心（项目级独立，随项目打包）；进入默认落在「项目通用设置」。
        </div>
        <div class="settings-desc" v-else>
          全局设置中心（首页/未进入项目时使用）：API 配置与 Prompt 模板为「用户级默认」，AI 用量统计全部项目的总用量；项目级设置（基本信息/世界时钟/导入导出/危险区）请在对应项目空间内进行。
        </div>
      </div>
      <div class="settings-body">
        <!-- 左侧子导航 -->
        <aside class="settings-nav">
          <router-link
            v-for="item in navItems"
            :key="item.path"
            :to="item.path"
            class="settings-nav-item"
            :class="{ active: isActive(item.path) }"
          >
            <el-icon><HIcon :name="item.icon" /></el-icon>
            <span>{{ item.name }}</span>
          </router-link>
        </aside>
        <!-- 右侧内容 -->
        <main class="settings-content">
          <router-view />
        </main>
      </div>
    </div>
  </div>
</template>

<script setup>
/**
 * 设置页外壳（前端布局重构 V1.0，设计文档 §3.10；双模式改造 V1.2）。
 * <p>职责：项目/全局双模式设置中心——左侧子导航（通用设置 / API 配置 / Prompt 模板 / AI 用量）+ 右侧内容区。
 * 项目模式：/project/:id/settings/*，设置均为项目级独立（随 .holzyn 包导入导出）；
 * 全局模式：/settings/*（首页顶栏「设置」进入），API/Prompt 为用户级默认、AI 用量为全部项目总用量。</p>
 */
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import ConsoleTopbar from '@/shared/components/console/ConsoleTopbar.vue'

const route = useRoute()
/** 当前项目 ID：项目空间内存在，全局设置（首页）为 null */
const projectId = computed(() => (route.params.id ? Number(route.params.id) : null))

// 设置子导航（四个子页）：项目模式 /project/:id/settings/*，全局模式 /settings/*
const base = computed(() => (projectId.value ? `/project/${projectId.value}/settings` : '/settings'))
const navItems = computed(() => [
  { name: '通用设置', icon: 'Setting', path: `${base.value}/general` },
  { name: 'API 配置', icon: 'Key', path: `${base.value}/apis` },
  { name: 'Prompt 模板', icon: 'Document', path: `${base.value}/prompts` },
  { name: 'AI 用量', icon: 'DataAnalysis', path: `${base.value}/usage` }
])

/** 子导航激活判断 */
function isActive(path) {
  return route.path === path || route.path.startsWith(path + '/')
}
</script>

<style scoped>
.settings-page { min-height: 100vh; background: var(--bg-base, var(--bg-light)); }
.settings-view { max-width: 1200px; margin: 0 auto; padding: 24px 24px 32px; }
.settings-head { margin-bottom: 16px; }
.settings-title { font-size: 1.3rem; font-weight: 700; color: var(--text-primary); }
.settings-desc { font-size: 0.85rem; color: var(--text-secondary); margin-top: 4px; }
.settings-body { display: flex; gap: 16px; align-items: flex-start; }
.settings-nav { width: 180px; background: var(--bg-layer-1); border-radius: var(--radius-lg); border: 1px solid var(--border-light); padding: 8px; flex-shrink: 0; position: sticky; top: 0; }
.settings-nav-item { display: flex; align-items: center; gap: 8px; padding: 10px 12px; border-radius: 6px; color: var(--text-regular); cursor: pointer; font-size: 0.86rem; text-decoration: none; transition: all 0.2s; }
.settings-nav-item:hover { background: var(--bg-light); color: var(--brand-primary); }
.settings-nav-item.active { background: var(--brand-gradient-soft); color: var(--brand-primary); font-weight: 600; }
.settings-content { flex: 1; min-width: 0; }
</style>
