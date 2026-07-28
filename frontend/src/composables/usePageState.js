// 页面状态保持 composable
// 配合 usePageStateStore 使用，简化各页面接入状态持久化的代码量
// 用法：
//   const { loadField, saveField, clearPage } = usePageState('DataManagement')
//   // 初始化时加载（对象类型会与默认值合并）
//   const orderFilter = reactive(loadField('orderFilter', { source: '', orderNo: '' }))
//   const orderPage = reactive(loadField('orderPage', { page: 1, size: 10 }))
//   const activeTab = ref(loadField('activeTab', 'order'))
//   // 自动保存（深度监听）
//   watch(orderFilter, v => saveField('orderFilter', { ...v }), { deep: true })
//   watch(orderPage, v => saveField('orderPage', { ...v }), { deep: true })
//   watch(activeTab, v => saveField('activeTab', v))
import { usePageStateStore } from '@/store/pageState'

/**
 * @param {string} pageKey 页面唯一标识（建议用路由 name，如 'DataManagement'）
 */
export function usePageState(pageKey) {
  const store = usePageStateStore()

  /**
   * 加载某字段的状态
   * @param {string} field 字段名
   * @param {*} defaultValue 默认值（对象会与保存值合并，保证新增字段有默认值）
   * @returns {*} 合并后的状态
   */
  function loadField(field, defaultValue) {
    return store.getField(pageKey, field, defaultValue)
  }

  /**
   * 保存某字段的状态
   * @param {string} field 字段名
   * @param {*} value 状态值
   */
  function saveField(field, value) {
    store.setField(pageKey, field, value)
  }

  /**
   * 清空当前页面的所有保存状态（重置回默认）
   */
  function clearPage() {
    store.clearPage(pageKey)
  }

  return { loadField, saveField, clearPage }
}
