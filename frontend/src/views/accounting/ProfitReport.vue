<script setup>
// 多维度利润报表页：周期核算触发、统计卡片、利润趋势图、利润明细表
import { reactive, ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { calculateProfit, pageProfitReport } from '@/api/accounting'
import EChartsWrapper from '@/components/EChartsWrapper.vue'
import { useUserStore } from '@/store'

const userStore = useUserStore()
// 是否允许触发核算：仅 ADMIN/FINANCE 可触发，OPERATOR 只读
const canCalculate = computed(() => {
  const roles = userStore.roles || []
  return roles.some(r => ['ADMIN', 'FINANCE'].includes(r))
})

// 核算周期
const period = ref('')

// 统计卡片（mock 数据）
const stat = reactive({
  totalRevenue: 1286450.32,
  totalCost: 763210.55,
  totalProfit: 523239.77,
  avgProfitRate: 40.67
})

// 明细表
const tableData = ref([])
const total = ref(0)
const loading = ref(false)
const page = reactive({ page: 1, size: 10 })

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

// 利润趋势折线图配置（mock 数据）
const trendOption = computed(() => ({
  title: { text: '利润趋势', left: 'left' },
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

// 触发核算
async function handleCalculate() {
  if (!period.value) {
    ElMessage.warning('请先选择核算周期')
    return
  }
  try {
    await calculateProfit(period.value)
    ElMessage.success('核算完成')
    loadData()
  } catch (e) {
    // 错误已由请求拦截器统一处理
  }
}

// 加载利润明细
async function loadData() {
  loading.value = true
  try {
    const res = await pageProfitReport({ period: period.value, ...page })
    tableData.value = res.data?.records || []
    total.value = res.data?.total || 0
  } catch (e) {
    // 错误已由请求拦截器统一处理
  } finally {
    loading.value = false
  }
}

function handleSizeChange(size) {
  page.size = size
  page.page = 1
  loadData()
}

function handleCurrentChange(p) {
  page.page = p
  loadData()
}

onMounted(() => {
  loadData()
})
</script>

<template>
  <div>
    <!-- 顶部：核算周期选择与触发核算 -->
    <div class="page-card mb-16">
      <el-form :inline="true">
        <el-form-item label="核算周期">
          <el-date-picker
            v-model="period"
            type="month"
            format="YYYYMM"
            value-format="YYYYMM"
            placeholder="请选择核算周期"
            style="width: 180px"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" v-if="canCalculate" @click="handleCalculate">
            <el-icon><Promotion /></el-icon> 触发核算
          </el-button>
          <span v-else style="color: #909399; font-size: 13px">仅财务/管理员可触发核算</span>
        </el-form-item>
      </el-form>
    </div>

    <!-- 统计卡片 -->
    <div class="stat-row mb-16">
      <el-card shadow="hover" class="stat-card">
        <div class="stat-label">总收入</div>
        <div class="stat-value" style="color: #67c23a">¥ {{ formatMoney(stat.totalRevenue) }}</div>
      </el-card>
      <el-card shadow="hover" class="stat-card">
        <div class="stat-label">总成本</div>
        <div class="stat-value" style="color: #f56c6c">¥ {{ formatMoney(stat.totalCost) }}</div>
      </el-card>
      <el-card shadow="hover" class="stat-card">
        <div class="stat-label">总利润</div>
        <div class="stat-value" style="color: #409eff">¥ {{ formatMoney(stat.totalProfit) }}</div>
      </el-card>
      <el-card shadow="hover" class="stat-card">
        <div class="stat-label">平均利润率</div>
        <div class="stat-value" style="color: #e6a23c">{{ formatRate(stat.avgProfitRate) }}</div>
      </el-card>
    </div>

    <!-- 图表区：利润趋势折线图 -->
    <div class="page-card mb-16">
      <EChartsWrapper :option="trendOption" height="360px" />
    </div>

    <!-- 利润明细表格 -->
    <div class="page-card">
      <el-table :data="tableData" v-loading="loading" border stripe style="width: 100%">
        <el-table-column prop="orderNo" label="订单号" min-width="180" />
        <el-table-column prop="platform" label="平台" width="120" />
        <el-table-column prop="currency" label="币种" width="90" />
        <el-table-column label="原始金额" width="140" align="right">
          <template #default="{ row }">{{ formatMoney(row.originalAmount) }}</template>
        </el-table-column>
        <el-table-column label="人民币金额" width="140" align="right">
          <template #default="{ row }">{{ formatMoney(row.cnyAmount) }}</template>
        </el-table-column>
        <el-table-column label="分摊成本" width="140" align="right">
          <template #default="{ row }">{{ formatMoney(row.allocatedCost) }}</template>
        </el-table-column>
        <el-table-column label="利润" width="140" align="right">
          <template #default="{ row }">
            <span :style="{ color: row.profit >= 0 ? '#67c23a' : '#f56c6c' }">
              {{ formatMoney(row.profit) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="利润率" width="110" align="right">
          <template #default="{ row }">{{ formatRate(row.profitRate) }}</template>
        </el-table-column>
      </el-table>

      <!-- 分页 -->
      <div class="flex-between" style="margin-top: 16px">
        <span style="color: #909399">共 {{ total }} 条</span>
        <el-pagination
          v-model:current-page="page.page"
          v-model:page-size="page.size"
          :page-sizes="[10, 20, 50, 100]"
          :total="total"
          layout="sizes, prev, pager, next, jumper"
          @size-change="handleSizeChange"
          @current-change="handleCurrentChange"
        />
      </div>
    </div>
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
