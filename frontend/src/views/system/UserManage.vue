<script setup>
// 用户与权限管理页：用户的增删改查、重置密码、分配角色
import { reactive, ref, computed, watch, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  pageUser,
  createUser,
  updateUser,
  deleteUser,
  resetPassword,
  assignRoles
} from '@/api/system'
import request from '@/utils/request'

// 搜索条件
const search = reactive({
  keyword: '',
  status: '',
  roleCode: ''
})

// 分页与表格数据
const tableData = ref([])
const total = ref(0)
const loading = ref(false)
const page = reactive({ page: 1, size: 10 })

// 新增/编辑弹窗
const dialogVisible = ref(false)
const dialogTitle = ref('新增用户')
const isEdit = ref(false)
const formRef = ref(null)
const form = reactive({
  id: null,
  username: '',
  password: '',
  realName: '',
  phone: '',
  email: '',
  deptId: null,
  status: 1
})

// 部门列表（用于下拉选择与表格展示）
const deptList = ref([])
const deptMap = computed(() => {
  const map = {}
  deptList.value.forEach(d => { map[d.id] = d.deptName })
  return map
})

// 加载部门列表
async function loadDeptList() {
  try {
    const res = await request({ url: '/system/dept/list', method: 'get' })
    deptList.value = res.data || []
  } catch (e) {
    // 错误已由请求拦截器统一处理
  }
}

// 部门名称格式化
function formatDept(deptId) {
  if (deptId == null) return '-'
  return deptMap.value[deptId] || `部门#${deptId}`
}

// 表单校验规则：密码仅新增必填
const rules = computed(() => ({
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: isEdit.value
    ? []
    : [{ required: true, message: '请输入密码', trigger: 'blur' }],
  realName: [{ required: true, message: '请输入真实姓名', trigger: 'blur' }],
  phone: [{ pattern: /^1[3-9]\d{9}$/, message: '手机号格式不正确', trigger: 'blur' }],
  email: [{ type: 'email', message: '邮箱格式不正确', trigger: 'blur' }]
}))

// 重置密码弹窗
const resetDialogVisible = ref(false)
const resetForm = reactive({ id: null, username: '', newPassword: '' })
const resetFormRef = ref(null)
const resetRules = {
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 6, message: '密码至少6位', trigger: 'blur' }
  ]
}

// 分配角色弹窗
const roleDialogVisible = ref(false)
const roleForm = reactive({ id: null, username: '', roleCodes: [] })

// 角色代码 -> 中文标签映射（扩充到 6 种）
const roleOptions = [
  { label: '管理员', value: 'ADMIN' },
  { label: '财务', value: 'FINANCE' },
  { label: '审批经理', value: 'APPROVER' },
  { label: '出纳', value: 'CASHIER' },
  { label: '运营', value: 'OPERATOR' },
  { label: '普通员工', value: 'EMPLOYEE' }
]

// 时间格式化
function formatTime(t) {
  if (!t) return '-'
  return String(t).replace('T', ' ').split('.')[0]
}

// 加载用户列表
async function loadData() {
  loading.value = true
  try {
    const res = await pageUser({ ...search, ...page })
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
  search.keyword = ''
  search.status = ''
  search.roleCode = ''
  page.page = 1
  loadData()
}

// 打开新增弹窗
function handleAdd() {
  isEdit.value = false
  dialogTitle.value = '新增用户'
  Object.assign(form, {
    id: null,
    username: '',
    password: '',
    realName: '',
    phone: '',
    email: '',
    deptId: null,
    status: 1
  })
  dialogVisible.value = true
}

// 打开编辑弹窗
function handleEdit(row) {
  isEdit.value = true
  dialogTitle.value = '编辑用户'
  Object.assign(form, {
    id: row.id,
    username: row.username,
    password: '',
    realName: row.realName,
    phone: row.phone,
    email: row.email,
    deptId: row.deptId,
    status: row.status
  })
  dialogVisible.value = true
}

// 提交新增/编辑表单
function handleSubmit() {
  if (!formRef.value) return
  formRef.value.validate(async (valid) => {
    if (!valid) return
    try {
      if (isEdit.value) {
        await updateUser(form)
        ElMessage.success('修改成功')
      } else {
        await createUser(form)
        ElMessage.success('新增成功')
      }
      dialogVisible.value = false
      loadData()
    } catch (e) {
      // 错误已由请求拦截器统一处理
    }
  })
}

// 删除用户
function handleDelete(row) {
  ElMessageBox.confirm(`确定删除用户「${row.username}」吗？`, '提示', {
    confirmButtonText: '确定',
    cancelButtonText: '取消',
    type: 'warning'
  })
    .then(async () => {
      try {
        await deleteUser(row.id)
        ElMessage.success('删除成功')
        loadData()
      } catch (e) {
        // 错误已由请求拦截器统一处理
      }
    })
    .catch(() => {})
}

// 打开重置密码弹窗
function handleResetPwd(row) {
  resetForm.id = row.id
  resetForm.username = row.username
  resetForm.newPassword = ''
  resetDialogVisible.value = true
}

// 提交重置密码
function submitReset() {
  if (!resetFormRef.value) return
  resetFormRef.value.validate(async (valid) => {
    if (!valid) return
    try {
      await resetPassword(resetForm.id, resetForm.newPassword)
      ElMessage.success('密码重置成功')
      resetDialogVisible.value = false
    } catch (e) {
      // 错误已由请求拦截器统一处理
    }
  })
}

// 打开分配角色弹窗
function handleAssignRoles(row) {
  roleForm.id = row.id
  roleForm.username = row.username
  // roleIds 在数据库中存储为 JSON 字符串（如 ["ADMIN"]），需解析为数组
  if (row.roleIds) {
    try {
      roleForm.roleCodes = typeof row.roleIds === 'string'
        ? JSON.parse(row.roleIds)
        : row.roleIds
    } catch {
      roleForm.roleCodes = []
    }
  } else {
    roleForm.roleCodes = []
  }
  roleDialogVisible.value = true
}

// 提交分配角色
async function submitRoles() {
  try {
    await assignRoles(roleForm.id, roleForm.roleCodes)
    ElMessage.success('角色分配成功')
    roleDialogVisible.value = false
    loadData()
  } catch (e) {
    // 错误已由请求拦截器统一处理
  }
}

// 格式化角色标签展示
function formatRoles(roleIds) {
  if (!roleIds) return '-'
  try {
    const codes = typeof roleIds === 'string' ? JSON.parse(roleIds) : roleIds
    return codes.map(code => {
      const opt = roleOptions.find(o => o.value === code)
      return opt ? opt.label : code
    }).join('、')
  } catch {
    return '-'
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
  loadDeptList()
  loadData()
})
</script>

<template>
  <div class="page-card">
    <!-- 搜索栏 + 操作按钮 -->
    <div class="flex-between mb-16">
      <el-form :inline="true" :model="search">
        <el-form-item label="关键词">
          <el-input
            v-model="search.keyword"
            placeholder="用户名 / 真实姓名"
            clearable
            @keyup.enter="handleSearch"
          />
        </el-form-item>
        <el-form-item label="角色">
          <el-select v-model="search.roleCode" placeholder="全部角色" clearable style="width: 140px">
            <el-option
              v-for="opt in roleOptions"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="search.status" placeholder="全部" clearable style="width: 120px">
            <el-option label="启用" :value="1" />
            <el-option label="禁用" :value="0" />
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
      <el-button type="primary" @click="handleAdd">
        <el-icon><Plus /></el-icon> 新增用户
      </el-button>
    </div>

    <!-- 用户表格 -->
    <el-table :data="tableData" v-loading="loading" border stripe style="width: 100%">
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="username" label="用户名" min-width="120" />
      <el-table-column prop="realName" label="真实姓名" min-width="120" />
      <el-table-column prop="phone" label="手机号" min-width="130" />
      <el-table-column label="部门" min-width="120">
        <template #default="{ row }">{{ formatDept(row.deptId) }}</template>
      </el-table-column>
      <el-table-column label="角色" min-width="120" show-overflow-tooltip>
        <template #default="{ row }">{{ formatRoles(row.roleIds) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'danger'">
            {{ row.status === 1 ? '启用' : '禁用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="创建时间" width="170">
        <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="300" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="handleEdit(row)">编辑</el-button>
          <el-button link type="danger" @click="handleDelete(row)">删除</el-button>
          <el-button link type="warning" @click="handleResetPwd(row)">重置密码</el-button>
          <el-button link type="success" @click="handleAssignRoles(row)">分配角色</el-button>
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
    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="520px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" :disabled="isEdit" placeholder="请输入用户名" />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            show-password
            :placeholder="isEdit ? '留空则不修改' : '请输入密码'"
          />
        </el-form-item>
        <el-form-item label="真实姓名" prop="realName">
          <el-input v-model="form.realName" placeholder="请输入真实姓名" />
        </el-form-item>
        <el-form-item label="手机号" prop="phone">
          <el-input v-model="form.phone" placeholder="请输入手机号" />
        </el-form-item>
        <el-form-item label="邮箱" prop="email">
          <el-input v-model="form.email" placeholder="请输入邮箱" />
        </el-form-item>
        <el-form-item label="部门" prop="deptId">
          <el-select v-model="form.deptId" placeholder="请选择部门" clearable filterable style="width: 100%">
            <el-option
              v-for="d in deptList"
              :key="d.id"
              :label="d.deptName"
              :value="d.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="状态" prop="status">
          <el-radio-group v-model="form.status">
            <el-radio :value="1">启用</el-radio>
            <el-radio :value="0">禁用</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSubmit">确定</el-button>
      </template>
    </el-dialog>

    <!-- 重置密码弹窗 -->
    <el-dialog v-model="resetDialogVisible" title="重置密码" width="420px" destroy-on-close>
      <el-form ref="resetFormRef" :model="resetForm" :rules="resetRules" label-width="90px">
        <el-form-item label="用户名">
          <el-input v-model="resetForm.username" disabled />
        </el-form-item>
        <el-form-item label="新密码" prop="newPassword">
          <el-input v-model="resetForm.newPassword" type="password" show-password placeholder="请输入新密码" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="resetDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submitReset">确定</el-button>
      </template>
    </el-dialog>

    <!-- 分配角色弹窗 -->
    <el-dialog v-model="roleDialogVisible" title="分配角色" width="420px" destroy-on-close>
      <el-form :model="roleForm" label-width="90px">
        <el-form-item label="用户名">
          <el-input v-model="roleForm.username" disabled />
        </el-form-item>
        <el-form-item label="角色">
          <el-select v-model="roleForm.roleCodes" multiple filterable placeholder="请选择角色" style="width: 100%">
            <el-option
              v-for="opt in roleOptions"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="roleDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submitRoles">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
</style>
