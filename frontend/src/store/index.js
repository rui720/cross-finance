// 全局状态存储：用户信息、Token
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { getToken, setToken, removeToken, getUser, setUser } from '@/utils/auth'

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

  function setLoginData(tokenValue, user) {
    token.value = tokenValue
    userInfo.value = user
    username.value = user?.username || ''
    setToken(tokenValue)
    setUser(user)
  }

  function reset() {
    token.value = ''
    userInfo.value = null
    username.value = ''
    removeToken()
  }

  return { token, userInfo, username, roles, hasRole, setLoginData, reset }
})
