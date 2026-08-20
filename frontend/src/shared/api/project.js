// 项目功能域 API：项目 CRUD / 世界观设定 / 角色卡生成 / .holzyn 导入导出
import http from './http'

// ==================== 项目/世界观 ====================
// 项目列表（分页参数 page/size 可选；画廊一次取 size=100 本地排序过滤）
export const fetchProjects = (params) => http.get('/api/projects', { params })
export const createProject = (data) => http.post('/api/projects', data)
export const fetchProject = (id) => http.get(`/api/projects/${id}`)
export const updateProject = (id, data) => http.put(`/api/projects/${id}`, data)
export const deleteProject = (id) => http.delete(`/api/projects/${id}`)
export const fetchWorldSetting = (projectId) => http.get(`/api/projects/${projectId}/world-setting`)
export const saveWorldSetting = (projectId, data) => http.post(`/api/projects/${projectId}/world-setting`, data)
export const generateCards = (projectId) => http.post(`/api/projects/${projectId}/generate-cards`, null, { timeout: 120000 })

// ==================== 文件导入建项目（上传解析/预览确认/AI补角色） ====================
// 上传文件解析（multipart，多文件 txt/md；不手动设 Content-Type 让 axios 自动带 boundary）
export const importParse = (files) => {
  const fd = new FormData()
  files.forEach((f) => fd.append(`files`, f))
  return http.post(`/api/projects/import/parse`, fd, { timeout: 0 }) // timeout 0 = 不限时
}
// SSE 流式解析：逐阶段推送 progress（done/total/label/chars），完成后 result，失败 error
export const importParseStream = (files, onProgress) => {
  const fd = new FormData()
  files.forEach((f) => fd.append(`files`, f))
  return fetch(`/api/projects/import/parse/stream`, { method: `POST`, body: fd })
    .then((res) => {
      if (!res.ok || !res.body) {
        return res.json().then((e) => { throw new Error(e?.message || `解析失败`) }).catch(() => { throw new Error(`解析失败`) })
      }
      const reader = res.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ``
      let result = null
      function pump() {
        return reader.read().then(({ done, value }) => {
          if (done) return result
          buffer += decoder.decode(value, { stream: true })
          const parts = buffer.split(`\n\n`)
          buffer = parts.pop() || ``
          for (const part of parts) {
            let event = ``
            let data = ``
            for (const line of part.split(`\n`)) {
              if (line.startsWith(`event:`)) event = line.slice(6).trim()
              else if (line.startsWith(`data:`)) data += line.slice(5).trim()
            }
            if (event === `progress` && onProgress) {
              try { onProgress(JSON.parse(data)) } catch (_) { /* 忽略单条进度解析失败 */ }
            } else if (event === `result`) {
              try { result = JSON.parse(data) } catch (_) { /* 忽略 */ }
            } else if (event === `error`) {
              let msg = `解析失败`
              try { msg = JSON.parse(data)?.message || msg } catch (_) { /* 忽略 */ }
              throw new Error(msg)
            }
          }
          return pump()
        })
      }
      return pump()
    })
}
// AI 自动生成符合世界观的角色档案
export const importGenerateCharacters = (data) => http.post(`/api/projects/import/characters/generate`, data, { timeout: 0 })
// 确认创建项目 + 世界观 + 角色
export const importConfirm = (data) => http.post(`/api/projects/import/confirm`, data, { timeout: 60000 })

// ==================== .holzyn 项目包导入/导出 ====================
// 导出 .holzyn 包（返回 blob；includeSensitive=是否含敏感数据；password=可选密码加密）
export const exportProjectPackage = (projectId, includeSensitive, password) => {
  const q = new URLSearchParams()
  if (includeSensitive) q.append('includeSensitive', 'true')
  if (password) q.append('password', password)
  return http.post(`/api/projects/${projectId}/export?${q.toString()}`, null, { responseType: 'blob', timeout: 60000 })
}
// 导入 .holzyn 包（multipart：file + password?）
export const importProjectPackage = (file, password) => {
  const fd = new FormData()
  fd.append('file', file)
  if (password) fd.append('password', password)
  return http.post('/api/projects/import/holzyn', fd, { timeout: 120000 })
}
