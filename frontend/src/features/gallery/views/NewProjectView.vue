<template>
  <div class="new-project-layout">
    <!-- 顶部栏：返回画廊 + 标题 + 设置入口（对齐项目空间顶栏风格） -->
    <header class="np-topbar">
      <div class="topbar-left">
        <el-button class="back-btn" text @click="$router.push('/')">
          <el-icon><HIcon name="ArrowLeft" /></el-icon>&nbsp;返回画廊
        </el-button>
        <span class="topbar-divider"></span>
        <div class="topbar-title">
          <span class="np-title">新增项目</span>
          <span v-if="breadcrumb" class="np-breadcrumb"> / {{ breadcrumb }}</span>
        </div>
      </div>
      <div class="topbar-right">
        <!-- 新增项目页「设置」入口：统一 SettingsButton → 打开全局设置弹窗（零路由依赖） -->
        <SettingsButton @click="openSettingsDialog" />
      </div>
    </header>

    <div class="np-body">
      <!-- ===== 悬浮岛式左侧侧边栏（圆角卡片、离边留白、柔和阴影，同项目空间布局） ===== -->
      <aside class="np-sidebar">
        <nav class="sidebar-nav">
          <div
            v-for="item in navItems"
            :key="item.key"
            class="nav-item"
            :class="{ active: active === item.key }"
            @click="active = item.key"
            :title="item.name"
          >
            <el-icon class="nav-icon"><HIcon :name="item.icon" /></el-icon>
            <span class="nav-text">{{ item.name }}</span>
            <span v-if="item.desc" class="nav-desc">{{ item.desc }}</span>
          </div>
        </nav>
        <div class="sidebar-foot">
          <el-tag size="small" type="info" effect="plain">创建方式</el-tag>
        </div>
      </aside>

      <!-- 主内容区：渲染当前创建方式 -->
      <main class="np-content">
        <ManualAddPanel v-if="active === 'manual'" />
        <FileParsePanel v-else-if="active === 'file'" @open-settings="openSettingsDialog" />
        <ProjectImportPanel v-else />
      </main>
    </div>

    <!-- 全局设置弹窗（项目空间外「设置」统一弹窗，零路由依赖） -->
    <GlobalSettingsDialog ref="settingsRef" />
  </div>
</template>

<script setup>
/**
 * 新增项目页（2026-08-19 重构：UI 改为与项目空间布局相同）。
 * <p>职责：新建项目的独立页面（不在项目空间内），不再分为平铺的三张方式卡片，
 * 而是采用与项目空间一致的「顶栏 + 悬浮岛式侧边栏 + 主内容区」布局：
 * 侧边导航栏含「手动添加 / 文件解析 / 项目导入」三个入口；内容区对应三个面板：
 * ① 手动添加：一次性填写项目信息 + 完整世界观设定（非必填，布局参考世界详情页左侧 Tab）；
 * ② 文件解析：选择文件 + 解析按钮 + 文件信息（文件名/格式/大小）+ 控制台日志区（SSE 实时输出后端解析日志）；
 * ③ 项目导入：导入 .holzyn 项目包（布局适配）。</p>
 * <p>手动添加 / 文件解析完成后均弹窗询问是否进行默认世界初始化（是→跳专属世界初始化页 /project/:id/init）。</p>
 */
import { ref, computed } from 'vue'
import { useRoute } from 'vue-router'
import SettingsButton from '@/shared/components/SettingsButton.vue'
import GlobalSettingsDialog from '@/features/settings/views/GlobalSettingsDialog.vue'
import ManualAddPanel from '../components/ManualAddPanel.vue'
import FileParsePanel from '../components/FileParsePanel.vue'
import ProjectImportPanel from '../components/ProjectImportPanel.vue'

const route = useRoute()
const active = ref('manual')

/** 侧边导航项（手动添加 / 文件解析 / 项目导入） */
const navItems = [
  { key: 'manual', name: '手动添加', icon: 'Edit', desc: '项目 + 世界观' },
  { key: 'file', name: '文件解析', icon: 'UploadFilled', desc: '上传文件 AI 解析' },
  { key: 'holzyn', name: '项目导入', icon: 'Download', desc: '.holzyn 包' }
]

/** 当前面包屑（由路由 meta 标题去掉 · HolzynActor 后缀） */
const breadcrumb = computed(() => {
  const t = route.meta?.title || ''
  return t.replace(' · HolzynActor', '')
})

// ===== 全局设置弹窗（项目空间外「设置」统一弹窗入口，零路由依赖） =====
const settingsRef = ref(null)

/** 打开全局设置弹窗（可指定初始 Tab，默认 apis=API 配置） */
function openSettingsDialog(tab = 'apis') {
  settingsRef.value?.open(tab)
}
</script>

<style scoped>
.new-project-layout { height: 100vh; display: flex; flex-direction: column; overflow: hidden; background: var(--bg-page, #eef1f6); }

/* ===== 顶部栏（52px，导航灰，对齐项目空间顶栏） ===== */
.np-topbar {
  height: 52px; background: var(--bg-nav); display: flex; align-items: center; justify-content: space-between;
  padding: 0 20px; flex-shrink: 0; box-shadow: var(--shadow-lv1); z-index: 5; border-bottom: 1px solid var(--border-l1);
}
.topbar-left { display: flex; align-items: center; gap: 12px; min-width: 0; }
.back-btn { color: var(--text-regular); }
.back-btn:hover { color: var(--accent-text); background: var(--accent-soft); }
.topbar-divider { width: 1px; height: 20px; background: var(--border-l2); }
.topbar-title { display: flex; align-items: center; gap: 6px; min-width: 0; white-space: nowrap; }
.np-title { font-weight: 700; font-size: 0.95rem; color: var(--text-primary); }
.np-breadcrumb { font-size: 0.82rem; color: var(--text-secondary); }
.topbar-right { display: flex; align-items: center; gap: 14px; }

/* ===== 主体 ===== */
.np-body { flex: 1; display: flex; overflow: hidden; }

/* ===== 悬浮岛式侧边栏（同项目空间布局） ===== */
.np-sidebar {
  width: 248px; margin: 16px 0 16px 16px; padding: 14px 10px;
  background: var(--bg-nav); border-radius: 18px; box-shadow: var(--shadow-lv2);
  border: 1px solid var(--border-l1); display: flex; flex-direction: column; justify-content: space-between;
  flex-shrink: 0; overflow-y: auto; position: relative; z-index: 3;
}
.sidebar-nav { flex: 1; display: flex; flex-direction: column; gap: 2px; }
.nav-item {
  display: flex; align-items: center; gap: 10px; padding: 11px 14px; border-radius: 12px;
  color: var(--text-regular); cursor: pointer; transition: all 0.18s; font-size: 0.9rem;
  white-space: nowrap; text-decoration: none; position: relative;
}
.nav-item:hover { background: var(--bg-hover); color: var(--text-primary); }
.nav-item.active {
  background: linear-gradient(135deg, var(--accent-soft), color-mix(in srgb, var(--accent) 8%, transparent));
  color: var(--accent-text); font-weight: 600; box-shadow: inset 0 0 0 1px var(--accent-border);
}
.nav-item.active::before {
  content: ''; position: absolute; left: 0; top: 20%; bottom: 20%; width: 3px;
  border-radius: 2px; background: var(--brand-primary, #1d5cff);
}
.nav-icon { font-size: 1.1rem; }
.nav-desc { margin-left: auto; font-size: 0.68rem; color: var(--text-tertiary); font-weight: 400; }
.sidebar-foot { padding: 12px 14px 4px; border-top: 1px solid var(--border-light, var(--border-l1)); margin-top: 10px; }

/* ===== 主内容区 ===== */
.np-content { flex: 1; background: transparent; overflow-y: auto; padding: 20px 24px; }
.np-sidebar::-webkit-scrollbar, .np-content::-webkit-scrollbar { width: 6px; }
.np-sidebar::-webkit-scrollbar-thumb { background: rgba(120, 140, 180, 0.18); border-radius: 3px; }
.np-content::-webkit-scrollbar-thumb { background: var(--scrollbar-thumb, #cdd5e2); border-radius: 3px; }
</style>
