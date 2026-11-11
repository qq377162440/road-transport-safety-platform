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
    // 服务商 / 超管代企业操作范本与业务台账时，把租户上下文切到「当前服务企业」。
    // 仅对范本、档案柜与业务闭环路径注入，避免影响 D5 平台侧的跨租户统计与全量查询。
    const target = localStorage.getItem('targetTenantId')
    const scoped = ['/archive/', '/tpl/', '/flow/'].some((p) => (config.url || '').startsWith(p))
    if (target && scoped) config.headers['X-Target-Tenant'] = target
    return config
  },
  (error) => Promise.reject(error),
)

// ---- 401 统一处理：access token（2 小时）过期时先用 refreshToken（7 天）静默续期并重放，
// 续期失败才踢回登录页。修复「隔一会儿没操作，打开页面就提示登录已失效」的体验问题。 ----

// 单飞：并发多个请求同时 401 时只发一次 /auth/refresh，其余等待同一个 Promise
let refreshing = null
// 提示防抖：避免一个页面并发 N 个 401 弹出 N 条「登录已失效」
let kickTimer = null

async function doRefresh() {
  const rt = localStorage.getItem('refreshToken')
  if (!rt) return false
  try {
    // 用独立的 axios 裸实例调刷新接口，避免走本文件的 401 拦截造成递归
    const res = await axios.post('/api/auth/refresh', { refreshToken: rt })
    const data = res.data
    if (data && data.code === 0 && data.data && data.data.accessToken) {
      localStorage.setItem('token', data.data.accessToken)
      if (data.data.refreshToken) localStorage.setItem('refreshToken', data.data.refreshToken)
      return true
    }
    return false
  } catch (e) {
    return false
  }
}

function kickToLogin() {
  localStorage.removeItem('token')
  localStorage.removeItem('refreshToken')
  if (router.currentRoute.value.path === '/login') return // 已在登录页不再重复弹
  if (kickTimer) { clearTimeout(kickTimer); kickTimer = null }
  kickTimer = setTimeout(() => {
    ElMessage.error('登录已失效，请重新登录')
    router.push('/login')
  }, 150)
}

/**
 * 401 统一入口。返回 true 表示已完成续期、调用方可重放原请求；
 * 返回 false 表示续期失败（或不应续期），已安排踢回登录页。
 */
async function handle401(config) {
  // 刷新接口自身失败、或已重试过一次仍 401：不再循环，直接踢回登录
  const url = (config && config.url) || ''
  if ((config && config._retried) || url.includes('/auth/refresh') || url.includes('/auth/login')
    || url.includes('/auth/dev-login') || url.includes('/auth/logout')) {
    kickToLogin()
    return false
  }
  refreshing = refreshing || doRefresh().finally(() => { refreshing = null })
  const okFlag = await refreshing
  if (!okFlag) {
    kickToLogin()
    return false
  }
  return true
}

/** 用续期后的新 token 重放原请求（会再次经过请求拦截器，Authorization 自动换新） */
function replay(config) {
  return request(Object.assign({}, config, { _retried: true }))
}

// 响应拦截器：统一处理 code 与 401
request.interceptors.response.use(
  async (response) => {
    const res = response.data
    // 后端统一返回 { code, msg, data }
    if (res && typeof res.code !== 'undefined') {
      if (res.code === 0) {
        return res
      }
      if (res.code === 401) {
        if (await handle401(response.config)) return replay(response.config)
        return Promise.reject(new Error(res.msg || '未登录'))
      }
      ElMessage.error(res.msg || '请求失败')
      return Promise.reject(new Error(res.msg || '请求失败'))
    }
    return res
  },
  async (error) => {
    if (error.response && error.response.status === 401) {
      if (await handle401(error.config)) return replay(error.config)
      return Promise.reject(error)
    }
    ElMessage.error(error.message || '网络错误')
    return Promise.reject(error)
  },
)

export default request
