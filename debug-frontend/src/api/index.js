/**
 * 调试前端 API 封装（fetch，2026-08-20）。
 * 只读为主：GET 封装 + 查询参数构建；错误统一抛「后端返回信息」。
 */

const BASE = '/api'

/** 构建查询字符串（跳过空值） */
export function buildQuery(params = {}) {
  const qs = new URLSearchParams()
  for (const [k, v] of Object.entries(params)) {
    if (v === undefined || v === null || v === '') continue
    qs.append(k, String(v))
  }
  const s = qs.toString()
  return s ? `?${s}` : ''
}

/** GET 请求（返回 JSON） */
export async function get(path, params = {}) {
  const resp = await fetch(`${BASE}${path}${buildQuery(params)}`, {
    headers: { Accept: 'application/json' }
  })
  if (!resp.ok) {
    let msg = `HTTP ${resp.status}`
    try {
      const body = await resp.json()
      if (body?.message) msg = body.message
    } catch (_) { /* 忽略解析失败 */ }
    throw new Error(msg)
  }
  return resp.json()
}

/** POST 请求（返回 JSON；M5 记忆巩固编排手动触发用） */
export async function post(path, body = {}) {
  const resp = await fetch(`${BASE}${path}`, {
    method: 'POST',
    headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  })
  if (!resp.ok) {
    let msg = `HTTP ${resp.status}`
    try {
      const b = await resp.json()
      if (b?.message) msg = b.message
    } catch (_) { /* 忽略解析失败 */ }
    throw new Error(msg)
  }
  return resp.json()
}

/** PUT 请求（返回 JSON；M4 状态调参保存覆盖用） */
export async function put(path, body = {}) {
  const resp = await fetch(`${BASE}${path}`, {
    method: 'PUT',
    headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  })
  if (!resp.ok) {
    let msg = `HTTP ${resp.status}`
    try {
      const b = await resp.json()
      if (b?.message) msg = b.message
    } catch (_) { /* 忽略解析失败 */ }
    throw new Error(msg)
  }
  return resp.json()
}

/** DELETE 请求（返回 JSON；M4 状态调参重置用） */
export async function del(path) {
  const resp = await fetch(`${BASE}${path}`, {
    method: 'DELETE',
    headers: { Accept: 'application/json' }
  })
  if (!resp.ok) {
    let msg = `HTTP ${resp.status}`
    try {
      const b = await resp.json()
      if (b?.message) msg = b.message
    } catch (_) { /* 忽略解析失败 */ }
    throw new Error(msg)
  }
  return resp.json()
}

/** 健康检查（后端在线探测，静默失败返回 null） */
export async function health() {
  try {
    const resp = await fetch(`${BASE}/health`, { headers: { Accept: 'application/json' } })
    if (!resp.ok) return null
    return resp.json()
  } catch (_) {
    return null
  }
}
