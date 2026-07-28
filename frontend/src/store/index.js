// 全局状态存储：用户信息、Token
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { getToken, setToken, removeToken, getUser, setUser, getRefreshToken, setRefreshToken } from '@/utils/auth'

export const useUserStore = defineStore('user', () => {
  const token = ref(getToken() || '')
  const userInfo = ref(getUser())

  const username = ref(userInfo.value?.username || '')

  // 角色列表：后端 roleIds 字段存储 JSON 数组字符串（如 ["ADMIN","FINANCE"]）
  const roles = computed(() => {
    const raw = userInfo.value?.roleIds
    if (!raw) return []
    if (Array.isArray(raw)) return raw
    try {
      return JSON.parse(raw)
    } catch {
      return []
    }
  })

  // 是否拥有某个角色
  function hasRole(code) {
    return roles.value.includes(code)
  }

  /**
   * 保存登录返回数据
   * @param accessToken 短期访问令牌
   * @param user 用户信息
   * @param refreshToken 长期刷新令牌（可选，仅登录/续期时返回）
   */
  function setLoginData(accessToken, user, refreshToken) {
    token.value = accessToken
    userInfo.value = user
    username.value = user?.username || ''
    setToken(accessToken)
    setUser(user)
    if (refreshToken) setRefreshToken(refreshToken)
  }

  function reset() {
    token.value = ''
    userInfo.value = null
    username.value = ''
    removeToken()
  }

  return { token, userInfo, username, roles, hasRole, setLoginData, reset }
})
