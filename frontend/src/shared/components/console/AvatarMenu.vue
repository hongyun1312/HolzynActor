<template>
  <!-- 本地账户下拉快捷面板（320px） -->
  <el-popover placement="bottom-end" :width="320" trigger="click" popper-class="avatar-popover">
    <template #reference>
      <div class="avatar-trigger">
        <el-avatar :size="26" :src="avatarUrl" style="background: var(--brand-gradient)">
          {{ avatarFallback }}
        </el-avatar>
        <span class="user-name">{{ displayName }}</span>
        <el-icon><HIcon name="ArrowDown" /></el-icon>
      </div>
    </template>

    <div class="avatar-panel">
      <!-- 用户信息区 -->
      <div class="panel-user">
        <el-avatar :size="46" :src="avatarUrl" style="background: var(--brand-gradient)">
          {{ avatarFallback }}
        </el-avatar>
        <div class="panel-user-info">
          <div class="nickname">{{ displayName }}</div>
          <div class="account-id" @click="copyId">
            ID: {{ accountId }}
            <el-icon :size="12"><HIcon name="CopyDocument" /></el-icon>
          </div>
          <el-tag size="small" type="primary" effect="light">本地账户</el-tag>
        </div>
      </div>

      <!-- 快捷入口区 -->
      <div class="panel-menu">
        <div v-for="item in menuItems" :key="item.path" class="menu-item" @click="go(item)">
          <el-icon :size="16"><HIcon :name="item.icon" /></el-icon>
          <span class="menu-label">{{ item.label }}</span>
          <el-icon class="menu-arrow"><HIcon name="ArrowRight" /></el-icon>
        </div>
      </div>

      <!-- 底部操作区：本地账户设置 -->
      <div class="panel-footer">
        <el-button class="account-btn" @click="openAccount">本地账户设置</el-button>
      </div>
    </div>
  </el-popover>
</template>

<script setup>
/**
 * 本地账户下拉快捷面板组件（替代原登录用户菜单）。
 * 职责：顶部栏本地账户头像下拉：用户信息区（头像/昵称/账户ID/本地账户标签）、
 * 快捷入口区（项目总览/新建项目）、底部「本地账户设置」（进入 /account 编辑）。
 */
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAccountStore } from '@/shared/store'

const router = useRouter()
const account = useAccountStore()

/** 展示昵称（缺省「本地用户」） */
const displayName = computed(() => account.nickname || '本地用户')

/** 头像 URL */
const avatarUrl = computed(() => account.avatarUrl || '')

/** 头像缺省文字 */
const avatarFallback = computed(() => (displayName.value ? displayName.value.charAt(0).toUpperCase() : 'H'))

/** 账户 ID（本地单用户=1，可复制） */
const accountId = computed(() => '1')

/** 快捷入口列表 */
const menuItems = computed(() => [
  { label: '项目总览', path: '/', icon: 'FolderOpened' },
  { label: '新建项目', path: '/project/new', icon: 'Plus' }
])

/** 复制账户 ID */
function copyId() {
  try {
    navigator.clipboard.writeText(accountId.value)
    ElMessage.success('账户 ID 已复制')
  } catch (_) { ElMessage.info('账户 ID: ' + accountId.value) }
}

/** 跳转快捷入口 */
function go(item) { router.push(item.path) }

/** 打开本地账户设置 */
function openAccount() { router.push('/account') }
</script>

<style scoped>
.avatar-trigger { display: flex; align-items: center; gap: 6px; cursor: pointer; color: var(--text-regular, #4b5563); }
.user-name { font-size: 0.85rem; }

.avatar-panel { padding: 4px 0; }
/* 用户信息区 */
.panel-user { display: flex; gap: 12px; padding: 8px 16px 14px; border-bottom: 1px solid var(--border-light); align-items: center; }
.panel-user-info { flex: 1; min-width: 0; }
.nickname { font-size: 0.95rem; font-weight: 600; color: var(--text-primary); }
.account-id { font-size: 0.78rem; color: var(--text-secondary); display: flex; align-items: center; gap: 4px; margin: 4px 0; cursor: pointer; }
.account-id:hover { color: var(--brand-primary); }
/* 快捷入口区 */
.panel-menu { padding: 6px 0; border-bottom: 1px solid var(--border-light); }
.menu-item { display: flex; align-items: center; gap: 10px; padding: 10px 16px; cursor: pointer; transition: background 0.2s; color: var(--text-regular); }
.menu-item:hover { background: var(--brand-gradient-soft); color: var(--brand-primary); }
.menu-label { flex: 1; font-size: 0.88rem; }
.menu-arrow { color: var(--text-placeholder); }
/* 底部操作区 */
.panel-footer { padding: 12px 16px; }
.account-btn { width: 100%; background: var(--bg-light); color: var(--text-regular); border: none; }
.account-btn:hover { background: var(--brand-gradient-soft); color: var(--brand-primary); }
</style>
