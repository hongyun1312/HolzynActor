// 新建项目「工作流」API：文件解析工作流 / 世界初始化工作流（均为 SSE 流式，前端控制台实时显示后端日志）
// 事件约定：log={level,message,time}（一行后端日志）、stage={name,index,total}（阶段进度）、
//           result=工作流结果 VO（parse→WorldParseResultVO / init→WorldInitResultVO）、error={message}

/**
 * 消费 SSE 事件流（fetch + ReadableStream，程序持续输出不会超时）。
 *
 * @param res       fetch 响应（res.ok 且 res.body 存在）
 * @param handlers  事件回调：{ onLog, onStage, onResult }
 * @return Promise<result>（result 事件载荷；遇 error 事件抛出 Error）
 */
async function consumeSse(res, handlers = {}) {
  const reader = res.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let result = null
  async function pump() {
    const { done, value } = await reader.read()
    if (done) return result
    buffer += decoder.decode(value, { stream: true })
    const parts = buffer.split('\n\n')
    buffer = parts.pop() || ''
    for (const part of parts) {
      let event = ''
      let data = ''
      for (const line of part.split('\n')) {
        if (line.startsWith('event:')) event = line.slice(6).trim()
        else if (line.startsWith('data:')) data += line.slice(5).trim()
      }
      if (event === 'log') {
        try { handlers.onLog?.(JSON.parse(data)) } catch (_) { /* 忽略单条日志解析失败 */ }
      } else if (event === 'stage') {
        try { handlers.onStage?.(JSON.parse(data)) } catch (_) { /* 忽略 */ }
      } else if (event === 'result') {
        try { result = JSON.parse(data) } catch (_) { /* 忽略 */ }
      } else if (event === 'error') {
        let msg = '工作流执行失败'
        try { msg = JSON.parse(data)?.message || msg } catch (_) { /* 忽略 */ }
        throw new Error(msg)
      }
    }
    return pump()
  }
  return pump()
}

/**
 * 文件解析工作流（SSE）：分段 →（可选扩写）→ 建项目落世界观表 → 知识库存储 → 角色分离入库。
 * 完成后 result={projectId, projectName, worldName, segmentChars, characterCount, knowledgeDocCount}。
 * 扩写由用户决定：expand=false（默认）分段后原样入库；expand=true 自动 AI 扩写不足 1500 字的分段。
 *
 * @param files    选中的原始 File 列表（txt/md）
 * @param expand   true=自动扩写不足 1500 字的分段 / false=不扩写（默认）
 * @param handlers 事件回调：{ onLog(line), onStage(stage), onResult(result) }
 * @return Promise<result>
 */
export const parseWorkflowStream = async (files, expand, handlers = {}) => {
  if (typeof expand === 'object' && expand !== null) {
    // 兼容旧调用（第二个参数直接传 handlers）
    handlers = expand
    expand = false
  }
  const fd = new FormData()
  files.forEach((f) => fd.append('files', f))
  fd.append('expand', expand ? 'true' : 'false')
  const res = await fetch('/api/projects/import/workflow', { method: 'POST', body: fd })
  if (!res.ok || !res.body) {
    let msg = '解析失败'
    try { msg = (await res.json())?.message || msg } catch (_) { /* 忽略 */ }
    throw new Error(msg)
  }
  return consumeSse(res, handlers)
}

/**
 * 世界初始化工作流（SSE）：地点 → 角色卡 → 字段字典+普通NPC → 关系拓扑 → 世界时间 → 知识向量化。
 * 完成后 result={projectId, locations, cards, npcs, relations, gameTimeText, vectorized}。
 *
 * @param projectId 项目 ID
 * @param rebuild   true=全量重建 / false=跳过已生成（幂等）
 * @param handlers  事件回调：{ onLog(line), onStage(stage), onResult(result) }
 * @return Promise<result>
 */
export const initWorkflowStream = async (projectId, rebuild, handlers = {}) => {
  const res = await fetch(`/api/projects/${projectId}/init/stream`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ rebuild: !!rebuild })
  })
  if (!res.ok || !res.body) {
    let msg = '世界初始化失败'
    try { msg = (await res.json())?.message || msg } catch (_) { /* 忽略 */ }
    throw new Error(msg)
  }
  return consumeSse(res, handlers)
}
