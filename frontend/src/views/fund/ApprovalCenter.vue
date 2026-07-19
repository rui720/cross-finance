<script setup>
// 审批工作台页：按状态分标签页查看付款申请，支持通过、驳回、标记已付款，并展示审批流
import { reactive, ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  pagePaymentApply,
  approvePayment,
  rejectPayment,
  markPaid
} from '@/api/fund'
import ApprovalFlow from '@/components/ApprovalFlow.vue'
import { useUserStore } from '@/store'

const userStore = useUserStore()
// 角色权限：审批通过/驳回仅 ADMIN/APPROVER；标记已付款仅 ADMIN/CASHIER
const canApprove = computed(() => {
  const roles = userStore.roles || []
  return roles.some(r => ['ADMIN', 'APPROVER'].includes(r))
})
const canMarkPaid = computed(() => {
  const roles = userStore.roles || []
  return roles.some(r => ['ADMIN', 'CASHIER'].includes(r))
})

// 标签页：0草稿 1待审批 2已通过 3已驳回 4已付款
const tabMap = {
  pending: { status: 1, label: '待审批' },
  approved: { status: 2, label: '已通过' },
  rejected: { status: 3, label: '已驳回' },
  paid: { status: 4, label: '已付款' }
}
const activeTab = ref('pending')

// 列表数据
const tableData = ref([])
const total = ref(0)
const loading = ref(false)
const page = reactive({ page: 1, size: 10 })

// 当前选中行
const currentRow = ref(null)

// 审批流弹窗
const flowVisible = ref(false)
const flowNodes = ref([])

// 驳回弹窗
const rejectVisible = ref(false)
const rejectReason = ref('')
const rejectId = ref(null)
const rejecting = ref(false)

// 审批状态映射：0草稿 1待审批 2已通过 3已驳回 4已付款
const statusMap = {
  0: { label: '草稿', type: 'info' },
  1: { label: '待审批', type: 'warning' },
  2: { label: '已通过', type: 'primary' },
  3: { label: '已驳回', type: 'danger' },
  4: { label: '已付款', type: 'success' }
}

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

// mock 审批流节点：根据申请状态生成
function buildFlowNodes(row) {
  // 节点：申请人提交 → 部门经理 → 财务总监 → 出纳
  const status = row.status
  // 已驳回：在部门经理处驳回
  if (status === 3) {
    return [
      { name: '申请人提交', role: '申请人', status: 'done', time: formatTime(row.applyTime), comment: '提交付款申请' },
      { name: '部门经理审批', role: '部门经理', status: 'rejected', time: formatTime(row.approvalTime), comment: row.rejectReason || '申请被驳回' },
      { name: '财务总监审批', role: '财务总监', status: 'pending' },
      { name: '出纳付款', role: '出纳', status: 'pending' }
    ]
  }
  // 已付款：全部完成
  if (status === 4) {
    return [
      { name: '申请人提交', role: '申请人', status: 'done', time: formatTime(row.applyTime), comment: '提交付款申请' },
      { name: '部门经理审批', role: '部门经理', status: 'done', time: formatTime(row.applyTime), comment: '同意' },
      { name: '财务总监审批', role: '财务总监', status: 'done', comment: '同意' },
      { name: '出纳付款', role: '出纳', status: 'done', time: formatTime(row.paidTime), comment: '已付款' }
    ]
  }
  // 已通过、待审批：按当前节点动态生成（mock，假定待审批/已通过停在部门经理/财务总监节点）
  if (status === 1) {
    return [
      { name: '申请人提交', role: '申请人', status: 'done', time: formatTime(row.applyTime), comment: '提交付款申请' },
      { name: '部门经理审批', role: '部门经理', status: 'current' },
      { name: '财务总监审批', role: '财务总监', status: 'pending' },
      { name: '出纳付款', role: '出纳', status: 'pending' }
    ]
  }
  if (status === 2) {
    return [
      { name: '申请人提交', role: '申请人', status: 'done', time: formatTime(row.applyTime), comment: '提交付款申请' },
      { name: '部门经理审批', role: '部门经理', status: 'done', comment: '同意' },
      { name: '财务总监审批', role: '财务总监', status: 'done', comment: '同意' },
      { name: '出纳付款', role: '出纳', status: 'current' }
    ]
  }
  return []
}

// 加载列表
async function loadData() {
  loading.value = true
  try {
    const status = tabMap[activeTab.value].status
    const res = await pagePaymentApply({ ...page, status })
    tableData.value = res.data?.records || []
    total.value = res.data?.total || 0
  } catch (e) {
    // 错误已由请求拦截器统一处理
  } finally {
    loading.value = false
  }
}

// 切换标签页
function handleTabChange() {
  page.page = 1
  loadData()
}

// 通过审批（二次确认）
function handleApprove(row) {
  ElMessageBox.confirm(`确定通过申请单「${row.applyNo}」吗？`, '审批确认', {
    confirmButtonText: '确定通过',
    cancelButtonText: '取消',
    type: 'warning'
  })
    .then(async () => {
      try {
        await approvePayment(row.id)
        ElMessage.success('审批通过')
        loadData()
      } catch (e) {
        // 错误已由请求拦截器统一处理
      }
    })
    .catch(() => {})
}

// 打开驳回弹窗
function handleOpenReject(row) {
  rejectId.value = row.id
  rejectReason.value = ''
  rejectVisible.value = true
}

// 提交驳回
async function handleReject() {
  if (!rejectReason.value.trim()) {
    ElMessage.warning('请输入驳回原因')
    return
  }
  rejecting.value = true
  try {
    await rejectPayment(rejectId.value, rejectReason.value.trim())
    ElMessage.success('已驳回')
    rejectVisible.value = false
    loadData()
  } catch (e) {
    // 错误已由请求拦截器统一处理
  } finally {
    rejecting.value = false
  }
}

// 标记已付款（二次确认）
function handleMarkPaid(row) {
  ElMessageBox.confirm(`确定将申请单「${row.applyNo}」标记为已付款吗？`, '付款确认', {
    confirmButtonText: '确定',
    cancelButtonText: '取消',
    type: 'warning'
  })
    .then(async () => {
      try {
        await markPaid(row.id)
        ElMessage.success('已标记为已付款')
        loadData()
      } catch (e) {
        // 错误已由请求拦截器统一处理
      }
    })
    .catch(() => {})
}

// 查看审批流
function handleViewFlow(row) {
  currentRow.value = row
  flowNodes.value = buildFlowNodes(row)
  flowVisible.value = true
}

// 选中行
function handleRowClick(row) {
  currentRow.value = row
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
  <div class="page-card">
    <!-- 顶部标签页 -->
    <el-tabs v-model="activeTab" @tab-change="handleTabChange" class="mb-16">
      <el-tab-pane label="待审批" name="pending" />
      <el-tab-pane label="已通过" name="approved" />
      <el-tab-pane label="已驳回" name="rejected" />
      <el-tab-pane label="已付款" name="paid" />
    </el-tabs>

    <!-- 审批列表 -->
    <el-table
      :data="tableData"
      v-loading="loading"
      border
      stripe
      highlight-current-row
      style="width: 100%"
      @row-click="handleRowClick"
    >
      <el-table-column prop="applyNo" label="申请单号" min-width="160" show-overflow-tooltip />
      <el-table-column prop="payee" label="收款方" min-width="160" show-overflow-tooltip />
      <el-table-column prop="currency" label="币种" width="90" />
      <el-table-column label="金额" width="160" align="right">
        <template #default="{ row }">{{ formatMoney(row.amount) }}</template>
      </el-table-column>
      <el-table-column prop="applicant" label="申请人" width="120" />
      <el-table-column label="申请时间" width="170">
        <template #default="{ row }">{{ formatTime(row.applyTime) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="240" fixed="right">
        <template #default="{ row }">
          <!-- 待审批：通过 / 驳回（仅审批经理/管理员可见） -->
          <template v-if="activeTab === 'pending'">
            <template v-if="canApprove">
              <el-button link type="primary" @click.stop="handleApprove(row)">通过</el-button>
              <el-button link type="danger" @click.stop="handleOpenReject(row)">驳回</el-button>
            </template>
            <span v-else style="color: #909399">无审批权限</span>
          </template>
          <!-- 已通过：标记已付款（仅出纳/管理员可见） -->
          <template v-else-if="activeTab === 'approved'">
            <el-button v-if="canMarkPaid" link type="success" @click.stop="handleMarkPaid(row)">标记已付款</el-button>
            <span v-else style="color: #909399">待出纳付款</span>
          </template>
          <!-- 已驳回：仅查看 -->
          <template v-else-if="activeTab === 'rejected'">
            <span style="color: #909399">已驳回</span>
          </template>
          <!-- 已付款：仅查看 -->
          <template v-else-if="activeTab === 'paid'">
            <span style="color: #909399">已完成</span>
          </template>
        </template>
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

    <!-- 选中行查看审批流入口 -->
    <div v-if="currentRow" style="margin-top: 16px">
      <el-button type="primary" plain @click="handleViewFlow(currentRow)">
        <el-icon><View /></el-icon> 查看审批流：{{ currentRow.applyNo }}
      </el-button>
    </div>

    <!-- 驳回弹窗 -->
    <el-dialog v-model="rejectVisible" title="驳回原因" width="480px">
      <el-input
        v-model="rejectReason"
        type="textarea"
        :rows="4"
        placeholder="请输入驳回原因"
        maxlength="200"
        show-word-limit
      />
      <template #footer>
        <el-button @click="rejectVisible = false">取消</el-button>
        <el-button type="danger" :loading="rejecting" @click="handleReject">确定驳回</el-button>
      </template>
    </el-dialog>

    <!-- 审批流弹窗 -->
    <el-dialog v-model="flowVisible" title="审批流程" width="780px">
      <ApprovalFlow :nodes="flowNodes" />
      <div v-if="currentRow" style="margin-top: 12px; color: #909399; font-size: 13px">
        申请单号：{{ currentRow.applyNo }} | 收款方：{{ currentRow.payee }} | 金额：{{ formatMoney(currentRow.amount) }} {{ currentRow.currency }}
      </div>
    </el-dialog>
  </div>
</template>

<style scoped>
</style>
