// 通用功能域 API：健康检查 / 通知（占位）
import http from './http'

// ==================== 健康检查 ====================
export const fetchHealth = () => http.get('/api/health')

// ==================== 通知（占位，供顶部铃铛组件使用） ====================
export const fetchNotifications = (params) => http.get('/api/notifications', { params }).catch(() => ({ list: [], total: 0 }))
export const fetchUnreadCount = () => http.get('/api/notifications/unread-count').catch(() => 0)
export const markNotificationRead = (id) => http.put(`/api/notifications/${id}/read`).catch(() => null)
export const markAllNotificationsRead = () => http.put('/api/notifications/read-all').catch(() => null)
export const createNotificationSocket = () => null
