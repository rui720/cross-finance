<script setup>
// 费用分摊模型配置页：维护分摊规则列表，支持新增、编辑、删除、启停切换
import { reactive, ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  pageRule,
  createRule,
  updateRule,
  deleteRule,
  toggleRuleEnabled
} from '@/api/accounting'
import { useUserStore } from '@/store'

const userStore = useUserStore()
// 是否允许写操作（新增/编辑/删除/启停）：仅 ADMIN/FINANCE
const canEdit = computed(() => {
  const roles = userStore.roles || []
  return roles.some(r => ['ADMIN', 'FINANCE'].includes(r))
})

// 列表数据
const tableData = ref([])
const total = ref(0)
const loading = ref(false)
const page = reactive({ page: 1, size: 10 })

// 弹窗状态
const dialogVisible = ref(false)
const dialogTitle = ref('新增分摊规则')
const submitting = ref(false)

// 表单数据
const defaultForm = () => ({
  id: null,
  ruleName: '',
  ruleType: 'WEIGHT',
  description: '',
  formula: ''
})
const form = reactive(defaultForm())
const formRef = ref(null)

const rules = {
  ruleName: [{ required: true, message: '请输入规则名称', trigger: 'blur' }],
  ruleType: [{ required: true, message: '请选择规则类型', trigger: 'change' }]
}

// 规则类型映射
const typeMap = {
  WEIGHT: { label: '按重量', type: 'primary' },
  AMOUNT: { label: '按金额', type: 'success' }
}

// 加载列表
async function loadData() {
  loading.value = true
  try {
    const res = await pageRule({ ...page })
    tableData.value = res.data?.records || []
    total.value = res.data?.total || 0
  } catch (e) {
    // 错误已由请求拦截器统一处理
  } finally {
    loading.value = false
  }
}

// 打开新增弹窗
function handleAdd() {
  Object.assign(form, defaultForm())
  dialogTitle.value = '新增分摊规则'
  dialogVisible.value = true
}

// 打开编辑弹窗
function handleEdit(row) {
  Object.assign(form, {
    id: row.id,
    ruleName: row.ruleName,
    ruleType: row.ruleType,
    description: row.description,
    formula: row.formula
  })
  dialogTitle.value = '编辑分摊规则'
  dialogVisible.value = true
}

// 提交表单
async function handleSubmit() {
  if (!formRef.value) return
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    submitting.value = true
    try {
      if (form.id) {
        await updateRule({ ...form })
        ElMessage.success('更新成功')
      } else {
        await createRule({ ...form })
        ElMessage.success('新增成功')
      }
      dialogVisible.value = false
      loadData()
    } catch (e) {
      // 错误已由请求拦截器统一处理
    } finally {
      submitting.value = false
    }
  })
}

// 删除二次确认
function handleDelete(row) {
  ElMessageBox.confirm(`确定要删除规则「${row.ruleName}」吗？`, '删除确认', {
    confirmButtonText: '确定',
    cancelButtonText: '取消',
    type: 'warning'
  })
    .then(async () => {
      try {
        await deleteRule(row.id)
        ElMessage.success('删除成功')
        loadData()
      } catch (e) {
        // 错误已由请求拦截器统一处理
      }
    })
    .catch(() => {})
}

// 启停切换
async function handleToggleEnabled(row) {
  const oldVal = row.enabled === 1 ? 0 : 1
  try {
    await toggleRuleEnabled(row.id, row.enabled)
    ElMessage.success(row.enabled === 1 ? '已启用' : '已停用')
  } catch (e) {
    // 失败回滚状态
    row.enabled = oldVal
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
  <div class="page-card">
    <!-- 顶部操作 -->
    <div class="flex-between mb-16">
      <span class="page-title">费用分摊模型配置</span>
      <el-button type="primary" v-if="canEdit" @click="handleAdd">
        <el-icon><Plus /></el-icon> 新增规则
      </el-button>
    </div>

    <!-- 规则列表 -->
    <el-table :data="tableData" v-loading="loading" border stripe style="width: 100%">
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column prop="ruleName" label="规则名称" min-width="160" show-overflow-tooltip />
      <el-table-column label="规则类型" width="120">
        <template #default="{ row }">
          <el-tag :type="(typeMap[row.ruleType] || {}).type || 'info'">
            {{ (typeMap[row.ruleType] || {}).label || row.ruleType }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="是否启用" width="110">
        <template #default="{ row }">
          <el-switch
            v-model="row.enabled"
            :active-value="1"
            :inactive-value="0"
            :disabled="!canEdit"
            @change="handleToggleEnabled(row)"
          />
        </template>
      </el-table-column>
      <el-table-column prop="description" label="描述" min-width="200" show-overflow-tooltip />
      <el-table-column v-if="canEdit" label="操作" width="160" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="handleEdit(row)">编辑</el-button>
          <el-button link type="danger" @click="handleDelete(row)">删除</el-button>
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

    <!-- 新增/编辑弹窗 -->
    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="560px" destroy-on-close>
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-width="100px"
      >
        <el-form-item label="规则名称" prop="ruleName">
          <el-input v-model="form.ruleName" placeholder="请输入规则名称" maxlength="50" />
        </el-form-item>
        <el-form-item label="规则类型" prop="ruleType">
          <el-select v-model="form.ruleType" placeholder="请选择规则类型" style="width: 100%">
            <el-option label="按重量" value="WEIGHT" />
            <el-option label="按金额" value="AMOUNT" />
          </el-select>
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" placeholder="请输入描述" maxlength="200" />
        </el-form-item>
        <el-form-item label="分摊公式">
          <el-input
            v-model="form.formula"
            type="textarea"
            :rows="4"
            placeholder="请输入分摊公式，例如：cost = totalCost * (weight / totalWeight)"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmit">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-title {
  font-size: 16px;
  font-weight: 600;
  color: #1f2937;
}
</style>
