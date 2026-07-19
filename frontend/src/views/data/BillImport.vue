<script setup>
// 平台账单导入与清洗页：拖拽上传 Excel、查看导入记录、对指定批次执行清洗
import { reactive, ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import request from '@/utils/request'

// 导入记录表格
const tableData = ref([])
const total = ref(0)
const loading = ref(false)
const uploading = ref(false)
const page = reactive({ page: 1, size: 10 })

// 时间格式化
function formatTime(t) {
  if (!t) return '-'
  return String(t).replace('T', ' ').split('.')[0]
}

// 状态标签类型映射
function statusType(status) {
  if (status === 'SUCCESS') return 'success'
  if (status === 'CLEANED') return 'primary'
  if (status === 'FAIL') return 'danger'
  return 'warning'
}

// 加载导入记录列表
async function loadData() {
  loading.value = true
  try {
    const res = await request({
      url: '/data/import/bill/page',
      method: 'get',
      params: page
    })
    tableData.value = res.data?.records || []
    total.value = res.data?.total || 0
  } catch (e) {
    // 错误已由请求拦截器统一处理
  } finally {
    loading.value = false
  }
}

// 自定义上传：调用导入接口
async function customUpload({ file, onSuccess, onError }) {
  uploading.value = true
  const formData = new FormData()
  formData.append('file', file)
  try {
    await request({
      url: '/data/import/bill',
      method: 'post',
      data: formData,
      headers: { 'Content-Type': 'multipart/form-data' }
    })
    ElMessage.success('导入成功')
    onSuccess && onSuccess(file)
    loadData()
  } catch (e) {
    onError && onError(e)
  } finally {
    uploading.value = false
  }
}

// 对指定批次执行清洗
async function handleClean(row) {
  try {
    await request({
      url: '/data/import/clean',
      method: 'post',
      params: { batchNo: row.batchNo }
    })
    ElMessage.success(`批次「${row.batchNo}」清洗完成`)
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

onMounted(() => {
  loadData()
})
</script>

<template>
  <div class="page-card">
    <!-- 上传区：拖拽上传 Excel -->
    <div class="mb-16" v-loading="uploading">
      <el-upload
        drag
        action=""
        :http-request="customUpload"
        :show-file-list="false"
        accept=".xls,.xlsx"
      >
        <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
        <div class="el-upload__text">将 Excel 文件拖到此处，或<em>点击上传</em></div>
        <template #tip>
          <div class="el-upload__tip">仅支持 .xls / .xlsx 文件，上传成功后自动刷新导入记录</div>
        </template>
      </el-upload>
    </div>

    <!-- 导入记录表格 -->
    <el-table :data="tableData" v-loading="loading" border stripe style="width: 100%">
      <el-table-column prop="batchNo" label="批次号" min-width="160" />
      <el-table-column prop="fileName" label="文件名" min-width="180" show-overflow-tooltip />
      <el-table-column prop="source" label="来源" width="120" />
      <el-table-column prop="recordCount" label="记录数" width="100" />
      <el-table-column label="导入时间" width="170">
        <template #default="{ row }">{{ formatTime(row.importTime) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag :type="statusType(row.status)">{{ row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="120" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="handleClean(row)">清洗</el-button>
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
  </div>
</template>

<style scoped>
</style>
