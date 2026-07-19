<script setup>
// 银行流水对账页：按批次号/状态查询流水，对选中行执行批量对账
import { reactive, ref, watch, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import request from '@/utils/request'

// 搜索条件
const search = reactive({
  batchNo: '',
  status: ''
})

// 分页与表格数据
const tableData = ref([])
const total = ref(0)
const loading = ref(false)
const page = reactive({ page: 1, size: 10 })

// 选中行
const selection = ref([])

// 金额格式化：保留2位小数，千分位
function formatMoney(num) {
  if (num === null || num === undefined || num === '') return '-'
  const n = Number(num)
  if (isNaN(n)) return '-'
  return n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

// 时间格式化
function formatTime(t) {
  if (!t) return '-'
  return String(t).replace('T', ' ').split('.')[0]
}

// 加载银行流水列表
async function loadData() {
  loading.value = true
  try {
    const res = await request({
      url: '/data/bank-flow/page',
      method: 'get',
      params: { ...search, ...page }
    })
    tableData.value = res.data?.records || []
    total.value = res.data?.total || 0
  } catch (e) {
    // 错误已由请求拦截器统一处理
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  page.page = 1
  loadData()
}

function handleReset() {
  search.batchNo = ''
  search.status = ''
  page.page = 1
  loadData()
}

function handleSelectionChange(rows) {
  selection.value = rows
}

// 对选中行执行对账
async function handleReconcile() {
  if (!selection.value.length) {
    ElMessage.warning('请至少选择一条记录')
    return
  }
  const ids = selection.value.map((r) => r.id)
  try {
    await request({
      url: '/data/bank-flow/reconcile',
      method: 'post',
      data: { ids }
    })
    ElMessage.success('对账完成')
    loadData()
  } catch (e) {
    // 错误已由请求拦截器统一处理
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

// watch 搜索条件变化自动查询
watch(
  () => ({ ...search }),
  () => {
    page.page = 1
    loadData()
  },
  { deep: true }
)

onMounted(() => {
  loadData()
})
</script>

<template>
  <div class="page-card">
    <!-- 搜索栏 + 操作按钮 -->
    <div class="flex-between mb-16">
      <el-form :inline="true" :model="search">
        <el-form-item label="批次号">
          <el-input
            v-model="search.batchNo"
            placeholder="请输入批次号"
            clearable
            @keyup.enter="handleSearch"
          />
        </el-form-item>
        <el-form-item label="对账状态">
          <el-select v-model="search.status" placeholder="全部" clearable style="width: 140px">
            <el-option label="已对账" value="RECONCILED" />
            <el-option label="未对账" value="UNRECONCILED" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="handleSearch">
            <el-icon><Search /></el-icon> 查询
          </el-button>
          <el-button @click="handleReset">
            <el-icon><Refresh /></el-icon> 重置
          </el-button>
        </el-form-item>
      </el-form>
      <el-button type="primary" :disabled="!selection.length" @click="handleReconcile">
        <el-icon><Check /></el-icon> 批量对账
      </el-button>
    </div>

    <!-- 流水表格 -->
    <el-table
      :data="tableData"
      v-loading="loading"
      border
      stripe
      style="width: 100%"
      @selection-change="handleSelectionChange"
    >
      <el-table-column type="selection" width="50" />
      <el-table-column prop="orderNo" label="订单号" min-width="180" />
      <el-table-column prop="platform" label="平台" width="120" />
      <el-table-column prop="currency" label="币种" width="90" />
      <el-table-column label="金额" width="140" align="right">
        <template #default="{ row }">{{ formatMoney(row.amount) }}</template>
      </el-table-column>
      <el-table-column label="结算金额" width="140" align="right">
        <template #default="{ row }">{{ formatMoney(row.settleAmount) }}</template>
      </el-table-column>
      <el-table-column prop="source" label="来源" width="110" />
      <el-table-column label="对账状态" width="110">
        <template #default="{ row }">
          <el-tag :type="row.status === 'RECONCILED' ? 'success' : 'warning'">
            {{ row.status === 'RECONCILED' ? '已对账' : '未对账' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="下单时间" width="170">
        <template #default="{ row }">{{ formatTime(row.orderTime) }}</template>
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
</template>

<style scoped>
</style>
