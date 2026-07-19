// Axios 请求拦截器封装
import axios from 'axios'
import { ElMessage } from 'element-plus'
import { getToken, removeToken } from './auth'
import router from '@/router'

const service = axios.create({
  baseURL: '/api',
  timeout: 30000,
  headers: {
    'Content-Type': 'application/json;charset=utf-8'
  }
})

// 防止并发请求 401 时重复跳转登录页
let isRedirecting = false

// 统一跳转登录页：清 token + 提示 + 跳转（仅一次）
function redirectToLogin(msg) {
  if (isRedirecting) return
  isRedirecting = true
  removeToken()
  ElMessage.error(msg || '登录已失效，请重新登录')
  router.push('/login').finally(() => {
    isRedirecting = false
  })
}

// 请求拦截器：自动携带 JWT
service.interceptors.request.use(
  (config) => {
    const token = getToken()
    if (token) {
      config.headers['Authorization'] = 'Bearer ' + token
    }
    return config
  },
  (error) => Promise.reject(error)
)

// 响应拦截器：统一处理业务码与异常
service.interceptors.response.use(
  (response) => {
    const res = response.data
    // 后端统一响应结构：{ code, msg, data }
    if (res.code === 200) {
      return res
    }
    // 401 未认证：JWT 过期/失效，直接跳登录页
    if (res.code === 401) {
      redirectToLogin(res.msg)
      return Promise.reject(new Error(res.msg || '未认证'))
    }
    // 403 权限不足（已登录但无权访问该资源）
    if (res.code === 403) {
      ElMessage.error(res.msg || '权限不足')
      return Promise.reject(new Error(res.msg || '权限不足'))
    }
    // 其他业务错误
    ElMessage.error(res.msg || '请求失败')
    return Promise.reject(new Error(res.msg || '请求失败'))
  },
  (error) => {
    const status = error.response?.status
    if (status === 401) {
      // JWT 过期/无效：跳转登录页，让用户重新登录
      redirectToLogin(error.response?.data?.msg)
    } else if (status === 403) {
      // 已登录但无权限访问该资源
      ElMessage.error(error.response?.data?.msg || '没有访问权限')
    } else if (error.code === 'ECONNABORTED') {
      ElMessage.error('请求超时，请稍后重试')
    } else {
      ElMessage.error(error.response?.data?.msg || '网络异常，请稍后重试')
    }
    return Promise.reject(error)
  }
)

export default service
