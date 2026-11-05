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
    }
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
