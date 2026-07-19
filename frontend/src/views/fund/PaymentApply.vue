<script setup>
// 付款申请发起页：列表展示付款申请，支持发起付款申请、查看申请详情
import { reactive, ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import {
  pagePaymentApply,
  getPaymentApply,
  submitPaymentApply,
  listBudgetPlan
} from '@/api/fund'

// 预算计划列表（用于下拉选择）
const budgetList = ref([])
const budgetMap = computed(() => {
  const map = {}
  budgetList.value.forEach(b => { map[b.id] = b })
  return map
})

// 格式化预算计划名称
function formatBudget(id) {
  if (id == null) return '-'
  const b = budgetMap.value[id]
  return b ? `${b.planName}（${b.period}）` : `预算#${id}`
}

// 加载预算计划列表
async function loadBudgetList() {
  try {
    const res = await listBudgetPlan()
    budgetList.value = res.data || []
  } catch (e) {
    // 错误已由请求拦截器统一处理
  }
}

// 列表数据
const tableData = ref([])
const total = ref(0)
const loading = ref(false)
const page = reactive({ page: 1, size: 10 })

// 申请表单弹窗
const applyDialogVisible = ref(false)
const submitting = ref(false)
const formRef = ref(null)
const defaultForm = () => ({
  payee: '',
  payeeAccount: '',
  currency: 'CNY',
  amount: null,
  reason: '',
  budgetPlanId: null
})
const form = reactive(defaultForm())
const rules = {
  payee: [{ required: true, message: '请输入收款方', trigger: 'blur' }],
  payeeAccount: [{ required: true, message: '请输入收款账号', trigger: 'blur' }],
  currency: [{ required: true, message: '请选择币种', trigger: 'change' }],
  amount: [
    { required: true, message: '请输入金额', trigger: 'blur' },
    { type: 'number', min: 0.01, message: '金额必须大于0', trigger: 'blur' }
  ],
  reason: [{ required: true, message: '请输入付款事由', trigger: 'blur' }]
}

// 详情弹窗
const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref({})

// 币种选项
const currencyOptions = [
  { label: '人民币 CNY', value: 'CNY' },
  { label: '美元 USD', value: 'USD' },
  { label: '欧元 EUR', value: 'EUR' },
  { label: '港币 HKD', value: 'HKD' },
  { label: '日元 JPY', value: 'JPY' }
]

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

// 加载列表
async function loadData() {
  loading.value = true
  try {
    const res = await pagePaymentApply({ ...page })
    tableData.value = res.data?.records || []
    total.value = res.data?.total || 0
  } catch (e) {
    // 错误已由请求拦截器统一处理
  } finally {
    loading.value = false
  }
}

// 打开申请弹窗
function handleOpenApply() {
  Object.assign(form, defaultForm())
  applyDialogVisible.value = true
}

// 提交付款申请
async function handleSubmit() {
  if (!formRef.value) return
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    submitting.value = true
    try {
      await submitPaymentApply({ ...form })
      ElMessage.success('付款申请提交成功')
      applyDialogVisible.value = false
      loadData()
    } catch (e) {
      // 错误已由请求拦截器统一处理
    } finally {
      submitting.value = false
    }
  })
}

// 查看详情
async function handleViewDetail(row) {
  detailVisible.value = true
  detailLoading.value = true
  detail.value = {}
  try {
    const res = await getPaymentApply(row.id)
    detail.value = res.data || row
  } catch (e) {
    // 错误已由请求拦截器统一处理
  } finally {
    detailLoading.value = false
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
  loadBudgetList()
  loadData()
})
</script>

<template>
  <div class="page-card">
    <!-- 顶部操作 -->
    <div class="flex-between mb-16">
      <span class="page-title">付款申请</span>
      <el-button type="primary" @click="handleOpenApply">
        <el-icon><Plus /></el-icon> 发起付款申请
      </el-button>
    </div>

    <!-- 申请列表 -->
    <el-table :data="tableData" v-loading="loading" border stripe style="width: 100%">
      <el-table-column prop="applyNo" label="申请单号" min-width="160" show-overflow-tooltip />
      <el-table-column prop="payee" label="收款方" min-width="160" show-overflow-tooltip />
      <el-table-column prop="currency" label="币种" width="90" />
      <el-table-column label="金额" width="160" align="right">
        <template #default="{ row }">{{ formatMoney(row.amount) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag :type="(statusMap[row.status] || {}).type || 'info'">
            {{ (statusMap[row.status] || {}).label || row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="applicant" label="申请人" width="120" />
      <el-table-column label="申请时间" width="170">
        <template #default="{ row }">{{ formatTime(row.applyTime) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="110" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="handleViewDetail(row)">查看详情</el-button>
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

    <!-- 发起付款申请弹窗 -->
    <el-dialog v-model="applyDialogVisible" title="发起付款申请" width="560px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="110px">
        <el-form-item label="收款方" prop="payee">
          <el-input v-model="form.payee" placeholder="请输入收款方名称" maxlength="100" />
        </el-form-item>
        <el-form-item label="收款账号" prop="payeeAccount">
          <el-input v-model="form.payeeAccount" placeholder="请输入收款账号" maxlength="50" />
        </el-form-item>
        <el-form-item label="币种" prop="currency">
          <el-select v-model="form.currency" placeholder="请选择币种" style="width: 100%">
            <el-option
              v-for="item in currencyOptions"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="金额" prop="amount">
          <el-input-number
            v-model="form.amount"
            :min="0"
            :precision="2"
            :step="100"
            controls-position="right"
            style="width: 100%"
            placeholder="请输入金额"
          />
        </el-form-item>
        <el-form-item label="付款事由" prop="reason">
          <el-input
            v-model="form.reason"
            type="textarea"
            :rows="3"
            placeholder="请输入付款事由"
            maxlength="200"
          />
        </el-form-item>
        <el-form-item label="关联预算计划">
          <el-select v-model="form.budgetPlanId" placeholder="请选择预算计划（可选）" clearable filterable style="width: 100%">
            <el-option
              v-for="b in budgetList"
              :key="b.id"
              :label="`${b.planName}（${b.period}）`"
              :value="b.id"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="applyDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmit">提交申请</el-button>
      </template>
    </el-dialog>

    <!-- 详情弹窗 -->
    <el-dialog v-model="detailVisible" title="付款申请详情" width="600px">
      <div v-loading="detailLoading" class="detail-wrap">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="申请单号">{{ detail.applyNo }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="(statusMap[detail.status] || {}).type || 'info'">
              {{ (statusMap[detail.status] || {}).label || detail.status }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="收款方">{{ detail.payee }}</el-descriptions-item>
          <el-descriptions-item label="收款账号">{{ detail.payeeAccount }}</el-descriptions-item>
          <el-descriptions-item label="币种">{{ detail.currency }}</el-descriptions-item>
          <el-descriptions-item label="金额">{{ formatMoney(detail.amount) }}</el-descriptions-item>
          <el-descriptions-item label="申请人">{{ detail.applicant }}</el-descriptions-item>
          <el-descriptions-item label="申请时间">{{ formatTime(detail.applyTime) }}</el-descriptions-item>
          <el-descriptions-item label="关联预算计划">{{ formatBudget(detail.budgetPlanId) }}</el-descriptions-item>
          <el-descriptions-item label="付款事由" :span="2">{{ detail.reason || '-' }}</el-descriptions-item>
        </el-descriptions>
      </div>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-title {
  font-size: 16px;
  font-weight: 600;
  color: #1f2937;
}
.detail-wrap {
  min-height: 100px;
}
</style>
