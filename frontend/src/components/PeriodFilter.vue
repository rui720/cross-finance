<template>
  <el-form-item :label="label">
    <el-radio-group v-model="mode" size="small" @change="handleModeChange">
      <el-radio-button value="MONTH">按月份</el-radio-button>
      <el-radio-button value="RANGE">按日期范围</el-radio-button>
    </el-radio-group>
  </el-form-item>
  <el-form-item v-if="mode === 'MONTH'" label="核算周期">
    <el-date-picker
      v-model="period"
      type="month"
      format="YYYYMM"
      value-format="YYYYMM"
      placeholder="请选择核算周期"
      :style="{ width: monthWidth }"
    />
  </el-form-item>
  <el-form-item v-else label="日期范围">
    <el-date-picker
      v-model="dateRange"
      type="daterange"
      range-separator="至"
      start-placeholder="开始日期"
      end-placeholder="结束日期"
      value-format="YYYY-MM-DD"
      :style="{ width: rangeWidth }"
    />
  </el-form-item>
</template>

<script setup>
import { ref, watch, computed } from 'vue'

/**
 * 通用核算周期筛选组件
 * <p>
 * 支持「按月份(YYYYMM) / 按日期范围」两种模式切换，表现形式与利润明细页一致。
 * 双向绑定通过 v-model:modelValue 实现，emit 的值结构：
 *   - MONTH 模式：{ mode: 'MONTH', period: 'YYYYMM', startDate, endDate }
 *   - RANGE 模式：{ mode: 'RANGE', period: '', startDate, endDate }
 *   - 未选择：{ mode, period: '', startDate: '', endDate: '' }
 * <p>
 * 其中 startDate/endDate 始终是 yyyy-MM-dd 字符串（MONTH 模式下自动转为当月起止日期），
 * 方便调用方直接传给后端接口（后端统一用 startDate/endDate 过滤）。
 * <p>
 * 对额外费用这类需要 period 字段的场景，可直接取返回值中的 period。
 */

const props = defineProps({
  /** v-model 绑定值：{ mode, period, startDate, endDate } */
  modelValue: { type: Object, default: () => ({}) },
  /** 标签文本（默认"核算周期"） */
  label: { type: String, default: '核算周期' },
  /** 月份选择器宽度 */
  monthWidth: { type: String, default: '180px' },
  /** 日期范围选择器宽度 */
  rangeWidth: { type: String, default: '280px' }
})

const emit = defineEmits(['update:modelValue', 'change'])

const mode = ref(props.modelValue?.mode || 'MONTH')
const period = ref(props.modelValue?.period || getLastMonthPeriod())
const dateRange = ref(props.modelValue?.dateRange || [])

/** 默认上个月 YYYYMM */
function getLastMonthPeriod() {
  const now = new Date()
  const year = now.getFullYear()
  const month = now.getMonth() // 0-11
  if (month === 0) return `${year - 1}12`
  return `${year}${String(month).padStart(2, '0')}`
}

/** YYYYMM → 当月起止日期 { firstDay, lastDay } */
function periodToRange(p) {
  if (!p || p.length !== 6) return { firstDay: '', lastDay: '' }
  const year = parseInt(p.substring(0, 4))
  const month = parseInt(p.substring(4, 6))
  const firstDay = `${p.substring(0, 4)}-${p.substring(4, 6)}-01`
  const lastDate = new Date(year, month, 0)
  const lastDay = `${lastDate.getFullYear()}-${String(lastDate.getMonth() + 1).padStart(2, '0')}-${String(lastDate.getDate()).padStart(2, '0')}`
  return { firstDay, lastDay }
}

/** 构造对外 emit 的值 */
function buildValue() {
  if (mode.value === 'MONTH') {
    const { firstDay, lastDay } = periodToRange(period.value)
    return { mode: 'MONTH', period: period.value || '', startDate: firstDay, endDate: lastDay }
  }
  // RANGE
  const startDate = dateRange.value?.[0] || ''
  const endDate = dateRange.value?.[1] || ''
  return { mode: 'RANGE', period: '', startDate, endDate }
}

function emitChange() {
  const v = buildValue()
  emit('update:modelValue', v)
  emit('change', v)
}

function handleModeChange() {
  // 切换模式即触发，让调用方重新查询
  emitChange()
}

// 内部值变化时同步对外
watch(period, emitChange)
watch(dateRange, emitChange, { deep: true })

// 外部值变化时同步内部（如持久化恢复）
watch(
  () => props.modelValue,
  v => {
    if (!v) return
    if (v.mode && v.mode !== mode.value) mode.value = v.mode
    if (v.period !== undefined && v.period !== period.value) period.value = v.period
    if (v.dateRange !== undefined) dateRange.value = v.dateRange
  },
  { deep: true }
)

// 初始化时 emit 一次，让调用方拿到默认值
emitChange()
</script>
