// 业财核算跨页面共享状态
// 用途：在「利润明细」页触发核算成功后，通知其他子页面（趋势/成本/诊断/聚合）
//      同步本次核算的周期；其他页面同步后仍可单独切换月份查看
import { defineStore } from 'pinia'
import { ref } from 'vue'

export const useAccountingStore = defineStore('accounting', () => {
  // 最近一次成功核算的上下文
  const lastCalcMode = ref('MONTH')      // 'MONTH' | 'RANGE'
  const lastCalcPeriod = ref('')         // YYYYMM（MONTH 模式）
  const lastCalcDateRange = ref([])      // [startDate, endDate]（RANGE 模式）
  // 核算版本号：每次成功核算 +1，其他页面 watch 此值触发同步
  // 初始为 0，表示尚未发生过核算；其他页面仅在 version > 0 且变化时同步
  const calcVersion = ref(0)

  /**
   * 通知核算成功，更新最近核算上下文并递增版本号。
   * 其他页面通过 watch calcVersion 同步周期，同步后仍可单独切换。
   *
   * @param mode    核算模式 'MONTH' | 'RANGE'
   * @param period  YYYYMM 月份周期（MONTH 模式传此值）
   * @param dateRange [startDate, endDate] 日期范围（RANGE 模式传此值）
   */
  function notifyCalcSuccess(mode, period, dateRange) {
    lastCalcMode.value = mode
    lastCalcPeriod.value = period || ''
    lastCalcDateRange.value = dateRange || []
    calcVersion.value++
  }

  return { lastCalcMode, lastCalcPeriod, lastCalcDateRange, calcVersion, notifyCalcSuccess }
})
