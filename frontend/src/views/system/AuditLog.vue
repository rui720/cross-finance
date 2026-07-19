<script setup>
// 操作审计日志页：按用户名与时间范围查询日志、查看详情、批量删除
import { reactive, ref, watch, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { pageAuditLog, batchDeleteAuditLog } from '@/api/system'

// 搜索条件
const search = reactive({
  username: '',
  timeRange: []
})

// 分页与表格数据
const tableData = ref([])
const total = ref(0)
const loading = ref(false)
const page = reactive({ page: 1, size: 10 })

// 详情弹窗
const detailVisible = ref(false)
const detailData = ref({})

// 选中行（批量删除）
const selection = ref([])

// 时间格式化
function formatTime(t) {
  if (!t) return '-'
  return String(t).replace('T', ' ').split('.')[0]
}

// 加载审计日志
async function loadData() {
  loading.value = true
  try {
    const params = {
      username: search.username,
      page: page.page,
      size: page.size
    }
    if (search.timeRange && search.timeRange.length === 2) {
      params.startTime = search.timeRange[0] + 'T00:00:00'
      params.endTime = search.timeRange[1] + 'T23:59:59'
    }
    const res = await pageAuditLog(params)
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
  search.username = ''
  search.timeRange = []
  page.page = 1
  loadData()
}

// 查看详情
function handleDetail(row) {
  detailData.value = row
  detailVisible.value = true
}

// 选中行变化
function handleSelectionChange(rows) {
  selection.value = rows
}

// 批量删除
function handleBatchDelete() {
  if (!selection.value.length) {
    ElMessage.warning('请至少选择一条记录')
    return
  }
  const ids = selection.value.map(r => r.id)
  ElMessageBox.confirm(`确定删除选中的 ${ids.length} 条日志吗？`, '批量删除', {
    confirmButtonText: '确定',
    cancelButtonText: '取消',
    type: 'warning'
  })
    .then(async () => {
      try {
        await batchDeleteAuditLog(ids)
        ElMessage.success('批量删除成功')
        loadData()
      } catch (e) {
        // 错误已由请求拦截器统一处理
      }
    })
    .catch(() => {})
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
        <el-form-item label="用户名">
          <el-input
            v-model="search.username"
            placeholder="请输入用户名"
            clearable
            @keyup.enter="handleSearch"
          />
        </el-form-item>
        <el-form-item label="操作时间">
          <el-date-picker
            v-model="search.timeRange"
            type="daterange"
            range-separator="至"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            value-format="YYYY-MM-DD"
            style="width: 260px"
          />
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
      <el-button type="danger" :disabled="!selection.length" @click="handleBatchDelete">
        <el-icon><Delete /></el-icon> 批量删除
      </el-button>
    </div>

    <!-- 日志表格 -->
    <el-table
      :data="tableData"
      v-loading="loading"
      border
      stripe
      style="width: 100%"
      @selection-change="handleSelectionChange"
    >
      <el-table-column type="selection" width="50" />
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column label="用户名" width="160">
        <template #default="{ row }">
          <span>{{ row.username || '-' }}</span>
          <el-tag v-if="row.userId" size="small" type="success" style="margin-left: 4px">已登录</el-tag>
          <el-tag v-else size="small" type="warning" style="margin-left: 4px">未登录</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="operation" label="操作描述" min-width="180" show-overflow-tooltip />
      <el-table-column prop="method" label="请求方法" min-width="160" show-overflow-tooltip />
      <el-table-column prop="ip" label="IP" width="140" />
      <el-table-column label="耗时(ms)" width="100" align="right">
        <template #default="{ row }">{{ row.costTime ?? '-' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'danger'">
            {{ row.status === 1 ? '成功' : '失败' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作时间" width="170">
        <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="90" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="handleDetail(row)">详情</el-button>
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

    <!-- 详情弹窗 -->
    <el-dialog v-model="detailVisible" title="日志详情" width="600px">
      <el-descriptions :column="1" border>
        <el-descriptions-item label="日志ID">{{ detailData.id }}</el-descriptions-item>
        <el-descriptions-item label="用户名">
          {{ detailData.username || '-' }}
          <el-tag v-if="detailData.userId" size="small" type="success" style="margin-left: 8px">已登录</el-tag>
          <el-tag v-else size="small" type="warning" style="margin-left: 8px">未登录</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="用户ID">{{ detailData.userId || '-' }}</el-descriptions-item>
        <el-descriptions-item label="操作描述">{{ detailData.operation }}</el-descriptions-item>
        <el-descriptions-item label="请求方法">{{ detailData.method }}</el-descriptions-item>
        <el-descriptions-item label="IP">{{ detailData.ip }}</el-descriptions-item>
        <el-descriptions-item label="耗时(ms)">{{ detailData.costTime ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="detailData.status === 1 ? 'success' : 'danger'">
            {{ detailData.status === 1 ? '成功' : '失败' }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="操作时间">{{ formatTime(detailData.createTime) }}</el-descriptions-item>
        <el-descriptions-item label="请求参数">
          <pre class="json-box">{{ detailData.params || '-' }}</pre>
        </el-descriptions-item>
        <el-descriptions-item label="错误信息">
          <pre class="json-box">{{ detailData.errorMsg || '-' }}</pre>
        </el-descriptions-item>
      </el-descriptions>
      <template #footer>
        <el-button type="primary" @click="detailVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.json-box {
  background-color: #f5f7fa;
  padding: 8px;
  border-radius: 4px;
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 200px;
  overflow: auto;
  margin: 0;
}
</style>
