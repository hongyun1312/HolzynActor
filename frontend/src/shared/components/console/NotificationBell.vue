<template>
  <!-- 顶栏通知铃铛：未读角标 + 下拉最近通知 + 全部已读 + 跳转通知中心 -->
  <el-popover placement="bottom-end" :width="340" trigger="click" popper-class="bell-popover">
    <template #reference>
      <el-badge :value="unreadCount" :max="99" :hidden="unreadCount === 0" class="topbar-bell">
        <el-icon :size="18"><HIcon name="Bell" /></el-icon>
      </el-badge>
    </template>
    <div class="bell-panel">
      <div class="bell-header">
        <span class="bell-title">通知</span>
        <div class="bell-actions">
          <el-button text type="primary" size="small" :disabled="unreadCount === 0" @click="onReadAll">全部已读</el-button>
          <el-button text size="small" @click="goCenter">查看全部</el-button>
        </div>
      </div>
      <div class="bell-list" v-loading="loading">
        <div v-if="items.length === 0" class="bell-empty">暂无通知</div>
        <div v-for="n in items" :key="n.id" class="bell-item" :class="{ unread: !n.isRead }" @click="onOpen(n)">
          <span class="bell-dot" :class="'dot-' + typeClass(n.type)"></span>
          <div class="bell-item-body">
            <div class="bell-item-top">
              <el-tag size="small" :type="typeTag(n.type)" effect="plain">{{ typeLabel(n.type) }}</el-tag>
              <span class="bell-time">{{ formatTime(n.createdAt) }}</span>
            </div>
            <div class="bell-item-title" :class="{ bold: !n.isRead }">{{ n.title }}</div>
            <div class="bell-item-snippet">{{ n.content }}</div>
          </div>
        </div>
      </div>
    </div>
  </el-popover>
</template>

<script setup>
/**
 * 顶栏通知铃铛组件。
 * 职责：展示未读角标，提供最近通知下拉列表、单条点击查看（自动已读）、全部已读、跳转通知中心，
 * 并订阅 /ws/notifications 实时刷新未读数量与列表。
 */
import { ref, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { fetchNotifications, fetchUnreadCount, markNotificationRead, markAllNotificationsRead, createNotificationSocket } from '@/shared/api'
import { useAccountStore } from '@/shared/store'
import { ElMessage } from 'element-plus'

// 当前用户 ID：本地单用户模式恒为 1（兼容后端通知接口 userId 参数）
const account = useAccountStore()
const USER_ID = () => account.userId

const router = useRouter()
const unreadCount = ref(0)
const items = ref([])
const loading = ref(false)
let socket = null

/** 通知类型 → 标签颜色 */
function typeTag(type) {
  if (type === 'alert') return 'danger'
  if (type === 'task_notify') return 'warning'
  if (type === 'update') return 'success'
  return 'primary'
}

/** 通知类型 → 展示文案 */
function typeLabel(type) {
  if (type === 'alert') return '告警'
  if (type === 'task_notify') return '任务'
  if (type === 'update') return '更新'
  return '公告'
}

/** 通知类型 → 圆点样式 */
function typeClass(type) {
  if (type === 'alert') return 'red'
  if (type === 'task_notify') return 'orange'
  if (type === 'update') return 'green'
  return 'blue'
}

/** 时间格式化：当天显示时分，其余显示月-日 时分 */
function formatTime(t) {
  if (!t) return ''
  const d = new Date(t)
  const pad = (x) => String(x).padStart(2, '0')
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

/** 加载未读数量与最近 8 条通知 */
async function loadData() {
  loading.value = true
  try {
    const [count, list] = await Promise.all([
      fetchUnreadCount(USER_ID()),
      fetchNotifications({ userId: USER_ID(), page: 1, size: 8 })
    ])
    unreadCount.value = count || 0
    items.value = list?.list || []
  } catch (_) { /* 通知加载失败静默处理，不影响顶栏 */ } finally { loading.value = false }
}

/** 点击单条：未读则标记已读，并跳转通知中心 */
function onOpen(n) {
  if (!n.isRead) {
    markNotificationRead(n.id, USER_ID()).catch(() => {})
    n.isRead = true
    unreadCount.value = Math.max(0, unreadCount.value - 1)
  }
  router.push('/')
}

/** 全部已读 */
async function onReadAll() {
  try {
    await markAllNotificationsRead(USER_ID())
    unreadCount.value = 0
    items.value.forEach((n) => { n.isRead = true })
    ElMessage.success('已全部标记为已读')
  } catch (_) { ElMessage.error('操作失败') }
}

/** 跳转通知中心 */
function goCenter() { router.push('/projects') }

onMounted(() => {
  loadData()
  // 订阅通知实时推送：收到新通知后刷新未读数与列表
  socket = createNotificationSocket(USER_ID(), () => { loadData() })
})
onUnmounted(() => { if (socket) socket.close() })
</script>

<style scoped>
.topbar-bell { cursor: pointer; display: flex; align-items: center; color: var(--text-regular); }
.topbar-bell:hover { color: var(--text-primary); }
.bell-panel { max-height: 420px; display: flex; flex-direction: column; }
.bell-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
.bell-title { font-weight: 600; color: var(--text-primary); }
.bell-list { overflow-y: auto; flex: 1; min-height: 120px; }
.bell-empty { text-align: center; color: var(--text-tertiary); padding: 24px 0; font-size: 0.85rem; }
.bell-item { display: flex; gap: 10px; padding: 10px 8px; border-radius: 6px; cursor: pointer; transition: background 0.2s; }
.bell-item:hover { background: var(--bg-hover); }
.bell-item.unread { background: var(--accent-soft); }
.bell-item.unread:hover { background: var(--accent-softer); }
.bell-dot { width: 8px; height: 8px; border-radius: 50%; margin-top: 6px; flex-shrink: 0; }
.dot-blue { background: #165DFF; }
.dot-green { background: #00b42a; }
.dot-red { background: #F53F3F; }
.dot-orange { background: #FF7D00; }
.bell-item-body { flex: 1; min-width: 0; }
.bell-item-top { display: flex; justify-content: space-between; align-items: center; }
.bell-time { font-size: 0.72rem; color: var(--text-tertiary); }
.bell-item-title { font-size: 0.86rem; color: var(--text-primary); margin-top: 4px; }
.bell-item-title.bold { font-weight: 600; }
.bell-item-snippet { font-size: 0.78rem; color: var(--text-secondary); margin-top: 2px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
</style>