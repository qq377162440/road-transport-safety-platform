import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '@/router'

// axios 实例
const request = axios.create({
  baseURL: '/api',
  timeout: 15000,
})

// 请求拦截器：自动携带 token
request.interceptors.request.use(
  (config) => {
    const token = localStorage.getItem('token')
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
      // 部分托管反向代理会剥离 Authorization 头，冗余携带自定义头兜底
      config.headers['X-Token'] = `Bearer ${token}`
    }
    // 服务商 / 超管代企业操作范本时，把租户上下文切到「当前服务企业」。
    // 仅对范本与档案柜路径注入，避免影响 D5 平台侧的跨租户统计与全量查询。
    const target = localStorage.getItem('targetTenantId')
    const scoped = ['/archive/', '/tpl/'].some((p) => (config.url || '').startsWith(p))
    if (target && scoped) config.headers['X-Target-Tenant'] = target
    return config
  },
  (error) => Promise.reject(error),
)

// 响应拦截器：统一处理 code 与 401
request.interceptors.response.use(
  (response) => {
    const res = response.data
    // 后端统一返回 { code, msg, data }
    if (res && typeof res.code !== 'undefined') {
      if (res.code === 0) {
        return res
      }
      if (res.code === 401) {
        ElMessage.error('登录已失效，请重新登录')
        localStorage.removeItem('token')
        router.push('/login')
        return Promise.reject(new Error(res.msg || '未登录'))
      }
      ElMessage.error(res.msg || '请求失败')
      return Promise.reject(new Error(res.msg || '请求失败'))
    }
    return res
  },
  (error) => {
    if (error.response && error.response.status === 401) {
      ElMessage.error('登录已失效，请重新登录')
      localStorage.removeItem('token')
      router.push('/login')
    } else {
      ElMessage.error(error.message || '网络错误')
    }
    return Promise.reject(error)
  },
)

export default request
