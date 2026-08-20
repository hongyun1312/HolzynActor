// 共享 HTTP 客户端：axios 实例 + 统一响应解包（R 格式 { code, message, data }）
import axios from 'axios'

const http = axios.create({ baseURL: '/', timeout: 10000 })
http.interceptors.response.use(
  (resp) => {
    const body = resp.data
    if (body && typeof body === 'object' && 'code' in body) {
      if (body.code === 200) return body.data
      return Promise.reject(new Error(body.message || '请求失败'))
    }
    return body
  },
  (err) => Promise.reject(err)
)

export default http
