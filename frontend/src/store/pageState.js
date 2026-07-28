// 页面状态保持 Store
// 按页面 key 存储筛选条件、页号等状态，自动持久化到 localStorage
// 用户切换页面再切回时，可恢复上次的筛选/分页状态，避免重复输入
import { defineStore } from 'pinia'
import { ref } from 'vue'

const STORAGE_KEY = 'cross-finance-page-state'

// 从 localStorage 读取已保存的所有页面状态
function loadFromStorage() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    return raw ? JSON.parse(raw) : {}
  } catch {
    return {}
  }
}

export const usePageStateStore = defineStore('pageState', () => {
  // 所有页面的状态集合：{ [pageKey]: { [field]: any } }
  const states = ref(loadFromStorage())

  // 写入 localStorage（容错：隐私模式或存储已满时静默失败）
  function persist() {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(states.value))
    } catch {
      // 忽略写入失败
    }
  }

  /**
   * 获取某页面的某个字段状态
   * @param {string} pageKey 页面唯一标识（建议用路由 name）
   * @param {string} field 字段名（如 'orderFilter'、'orderPage'、'activeTab'）
   * @param {*} defaultValue 默认值（对象会与保存值合并，基本类型直接返回保存值）
   * @returns {*} 合并后的状态
   */
  function getField(pageKey, field, defaultValue) {
    const pageState = states.value[pageKey] || {}
    const saved = pageState[field]
    if (saved === undefined || saved === null) {
      return defaultValue
    }
    // 对象类型：默认值与保存值合并（保存值优先），保证新增字段有默认值
    if (defaultValue && typeof defaultValue === 'object' && !Array.isArray(defaultValue)) {
      return { ...defaultValue, ...saved }
    }
    return saved
  }

  /**
   * 保存某页面的某个字段状态
   * @param {string} pageKey 页面唯一标识
   * @param {string} field 字段名
   * @param {*} value 状态值
   */
  function setField(pageKey, field, value) {
    if (!states.value[pageKey]) {
      states.value[pageKey] = {}
    }
    states.value[pageKey][field] = value
    persist()
  }

  /**
   * 清空某页面的所有保存状态（重置回默认）
   * @param {string} pageKey 页面唯一标识
   */
  function clearPage(pageKey) {
    delete states.value[pageKey]
    persist()
  }

  /**
   * 清空所有页面的保存状态（登出时调用）
   */
  function clearAll() {
    states.value = {}
    persist()
  }

  return { states, getField, setField, clearPage, clearAll }
})
