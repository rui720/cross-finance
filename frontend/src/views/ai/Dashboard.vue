<script setup>
// 经营全景驾驶舱页：周期选择、4个统计卡片、3个图表（趋势/饼图/柱状图），全部 mock 数据
import { ref, computed } from 'vue'
import EChartsWrapper from '@/components/EChartsWrapper.vue'

// 周期选择
const period = ref('')

// 统计卡片（mock 数据）
const stat = computed(() => ({
  revenue: 1286450.32,
  cost: 763210.55,
  profit: 523239.77,
  profitRate: (523239.77 / 1286450.32) * 100
}))

// 金额格式化：保留2位小数，千分位
function formatMoney(num) {
  if (num === null || num === undefined || num === '') return '-'
  const n = Number(num)
  if (isNaN(n)) return '-'
  return n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

// 利润率格式化
function formatRate(num) {
  if (num === null || num === undefined || num === '') return '-'
  const n = Number(num)
  if (isNaN(n)) return '-'
  return n.toFixed(2) + '%'
}

// 收入成本利润趋势图（折线图，12个月，mock 数据）
const trendOption = computed(() => ({
  title: { text: '收入成本利润趋势', left: 'left' },
  tooltip: { trigger: 'axis' },
  legend: { data: ['收入', '成本', '利润'], top: 0, right: 0 },
  grid: { left: 60, right: 20, top: 50, bottom: 40 },
  xAxis: {
    type: 'category',
    data: ['1月', '2月', '3月', '4月', '5月', '6月', '7月', '8月', '9月', '10月', '11月', '12月']
  },
  yAxis: { type: 'value', name: '金额(元)' },
  series: [
    {
      name: '收入',
      type: 'line',
      smooth: true,
      data: [82000, 93000, 105000, 98000, 112000, 128000, 135000, 121000, 140000, 156000, 148000, 162000]
    },
    {
      name: '成本',
      type: 'line',
      smooth: true,
      data: [52000, 56000, 61000, 59000, 66000, 73000, 78000, 71000, 82000, 89000, 85000, 92000]
    },
    {
      name: '利润',
      type: 'line',
      smooth: true,
      data: [30000, 37000, 44000, 39000, 46000, 55000, 57000, 50000, 58000, 67000, 63000, 70000]
    }
  ]
}))

// 各平台利润占比（饼图，mock 数据）
const platformPieOption = computed(() => ({
  title: { text: '各平台利润占比', left: 'left' },
  tooltip: { trigger: 'item', formatter: '{a} <br/>{b}: {c} ({d}%)' },
  legend: { orient: 'vertical', left: 'left', top: 'middle' },
  series: [
    {
      name: '平台利润',
      type: 'pie',
      radius: ['40%', '70%'],
      avoidLabelOverlap: false,
      itemStyle: { borderRadius: 8, borderColor: '#fff', borderWidth: 2 },
      label: { show: false, position: 'center' },
      emphasis: { label: { show: true, fontSize: 18, fontWeight: 'bold' } },
      labelLine: { show: false },
      data: [
        { value: 185600, name: '亚马逊' },
        { value: 142300, name: '速卖通' },
        { value: 98700, name: 'Shopify' },
        { value: 64500, name: 'Shopee' },
        { value: 32140, name: '其他' }
      ]
    }
  ]
}))

// 各币种收入分布（柱状图，mock 数据）
const currencyBarOption = computed(() => ({
  title: { text: '各币种收入分布', left: 'left' },
  tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
  legend: { data: ['收入'], top: 0, right: 0 },
  grid: { left: 60, right: 20, top: 50, bottom: 40 },
  xAxis: {
    type: 'category',
    data: ['CNY 人民币', 'USD 美元', 'EUR 欧元', 'HKD 港币', 'JPY 日元']
  },
  yAxis: { type: 'value', name: '金额(元)' },
  series: [
    {
      name: '收入',
      type: 'bar',
      barWidth: '50%',
      itemStyle: { color: '#409eff', borderRadius: [4, 4, 0, 0] },
      data: [586200, 412300, 168500, 86700, 32750]
    }
  ]
}))
</script>

<template>
  <div>
    <!-- 顶部：周期选择 -->
    <div class="page-card mb-16">
      <el-form :inline="true">
        <el-form-item label="统计周期">
          <el-date-picker
            v-model="period"
            type="month"
            format="YYYY-MM"
            value-format="YYYY-MM"
            placeholder="请选择统计周期"
            style="width: 200px"
          />
        </el-form-item>
      </el-form>
    </div>

    <!-- 4个统计卡片 -->
    <div class="stat-row mb-16">
      <el-card shadow="hover" class="stat-card">
        <div class="stat-label">本期收入</div>
        <div class="stat-value" style="color: #67c23a">¥ {{ formatMoney(stat.revenue) }}</div>
      </el-card>
      <el-card shadow="hover" class="stat-card">
        <div class="stat-label">本期成本</div>
        <div class="stat-value" style="color: #f56c6c">¥ {{ formatMoney(stat.cost) }}</div>
      </el-card>
      <el-card shadow="hover" class="stat-card">
        <div class="stat-label">本期利润</div>
        <div class="stat-value" style="color: #409eff">¥ {{ formatMoney(stat.profit) }}</div>
      </el-card>
      <el-card shadow="hover" class="stat-card">
        <div class="stat-label">利润率</div>
        <div class="stat-value" style="color: #e6a23c">{{ formatRate(stat.profitRate) }}</div>
      </el-card>
    </div>

    <!-- 图表区：每行2个图表 -->
    <el-row :gutter="16">
      <el-col :span="12">
        <div class="page-card mb-16">
          <EChartsWrapper :option="trendOption" height="360px" />
        </div>
      </el-col>
      <el-col :span="12">
        <div class="page-card mb-16">
          <EChartsWrapper :option="platformPieOption" height="360px" />
        </div>
      </el-col>
      <el-col :span="12">
        <div class="page-card mb-16">
          <EChartsWrapper :option="currencyBarOption" height="360px" />
        </div>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
.stat-row {
  display: flex;
  gap: 16px;
}
.stat-card {
  flex: 1;
}
.stat-label {
  font-size: 13px;
  color: #909399;
  margin-bottom: 8px;
}
.stat-value {
  font-size: 22px;
  font-weight: 600;
}
</style>
