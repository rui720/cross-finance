// Axios 请求拦截器封装
import axios from 'axios'
import { ElMessage } from 'element-plus'
import { getToken, setToken, getRefreshToken, setRefreshToken, removeToken } from './auth'
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

// ===== refreshToken 无感续期机制 =====
let isRefreshing = false
let refreshSubscribers = []

function subscribeTokenRefresh(cb) {
  refreshSubscribers.push(cb)
}

function onTokenRefreshed(newToken) {
  refreshSubscribers.forEach((cb) => cb(newToken))
  refreshSubscribers = []
}

function onRefreshFailed() {
  refreshSubscribers.forEach((cb) => cb(null))
  refreshSubscribers = []
}

// 调用 /auth/refresh 换取新 accessToken + refreshToken
function doRefreshToken() {
  return service.post('/auth/refresh', { refreshToken: getRefreshToken() })
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
    // blob 响应（文件下载）直接返回，不走业务码校验
    if (response.config.responseType === 'blob' || response.data instanceof Blob) {
      return response.data
    }
    const res = response.data
    // 后端统一响应结构：{ code, msg, data }
    if (res.code === 200) {
      return res
    }
    // 401 未认证：尝试用 refreshToken 续期，续期失败再跳登录
    if (res.code === 401) {
      // 防止重放请求后仍 401 形成无限循环：已重试过的请求直接跳登录
      if (response.config._retried) {
        redirectToLogin(res.msg)
        return Promise.reject(new Error(res.msg || '未认证'))
      }
      response.config._retried = true
      return handleTokenExpired(response.config)
        .then((newConfig) => service(newConfig))
        .catch(() => {
          redirectToLogin(res.msg)
          return Promise.reject(new Error(res.msg || '未认证'))
        })
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
      // 防止重放请求后仍 401 形成无限循环：已重试过的请求直接跳登录
      if (error.config?._retried) {
        redirectToLogin(error.response?.data?.msg)
        return Promise.reject(error)
      }
      error.config._retried = true
      // accessToken 过期：尝试 refreshToken 续期
      return handleTokenExpired(error.config)
        .then((newConfig) => service(newConfig))
        .catch(() => {
          redirectToLogin(error.response?.data?.msg)
          return Promise.reject(error)
        })
    } else if (status === 403) {
      ElMessage.error(error.response?.data?.msg || '没有访问权限')
    } else if (error.code === 'ECONNABORTED') {
      ElMessage.error('请求超时，请稍后重试')
    } else {
      ElMessage.error(error.response?.data?.msg || '网络异常，请稍后重试')
    }
    return Promise.reject(error)
  }
)

/**
 * 处理 accessToken 过期：
 * 1. 若已有续期请求在进行中，挂起当前请求等待新 token
 * 2. 否则发起续期请求；成功后重放所有挂起请求，失败则跳登录页
 */
function handleTokenExpired(config) {
  if (!getRefreshToken()) {
    return Promise.reject(new Error('无 refreshToken'))
  }
  // /auth/refresh 自身 401 直接失败，避免无限递归
  if (config.url && config.url.includes('/auth/refresh')) {
    return Promise.reject(new Error('refreshToken 已失效'))
  }
  if (isRefreshing) {
    // 已有续期请求进行中，当前请求挂起等待
    return new Promise((resolve) => {
      subscribeTokenRefresh((newToken) => {
        if (!newToken) {
          return resolve(Promise.reject(new Error('续期失败')))
        }
        config.headers['Authorization'] = 'Bearer ' + newToken
        resolve(config)
      })
    })
  }
  isRefreshing = true
  return doRefreshToken()
    .then((res) => {
      const data = res.data || {}
      const newAccessToken = data.accessToken || data.token
      const newRefreshToken = data.refreshToken
      if (!newAccessToken) {
        return Promise.reject(new Error('续期响应缺 accessToken'))
      }
      setToken(newAccessToken)
      if (newRefreshToken) setRefreshToken(newRefreshToken)
      onTokenRefreshed(newAccessToken)
      config.headers['Authorization'] = 'Bearer ' + newAccessToken
      return config
    })
    .catch((err) => {
      onRefreshFailed()
      return Promise.reject(err)
    })
    .finally(() => {
      isRefreshing = false
    })
}

export default service
