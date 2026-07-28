<script setup>
// 银行流水导入页：仅负责导入相关功能（上传、下载模板、导入记录、清洗、错误明细）
// 具体业务操作（查看、编辑、删除、对账）请在「数据管理」页面完成
import { reactive, ref, onMounted, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import axios from 'axios'
import request from '@/utils/request'
import { getToken } from '@/utils/auth'
import { usePageState } from '@/composables/usePageState'

// 页面状态保持（页号切换页面后自动恢复）
const { loadField, saveField } = usePageState('BankReconciliation')

// 导入记录表格
const tableData = ref([])
const total = ref(0)
const loading = ref(false)
const uploading = ref(false)
const page = reactive(loadField('page', { page: 1, size: 10 }))

// 错误明细对话框
const errorDialogVisible = ref(false)
const errorBatch = ref(null)

// 时间格式化
function formatTime(t) {
  if (!t) return '-'
  return String(t).replace('T', ' ').split('.')[0]
}

// 状态标签类型映射
function statusType(status) {
  if (status === 'CLEANED') return 'success'
  if (status === 'CLEANING') return 'warning'
  if (status === 'FAILED') return 'danger'
  return 'info'
}

function statusText(status) {
  return status || 'IMPORTED'
}

// 加载银行流水导入记录（仅 BANK 来源）
async function loadData() {
  loading.value = true
  try {
    const res = await request({
      url: '/data/import/bill/page',
      method: 'get',
      params: { ...page, sourceType: 'BANK' }
    })
    tableData.value = res.data?.records || []
    total.value = res.data?.total || 0
  } catch (e) {
    // 错误已由请求拦截器统一处理
  } finally {
    loading.value = false
  }
}

// 下载银行流水导入模板
async function downloadTemplate() {
  try {
    const res = await axios.get('/api/data/import/template/download', {
      params: { sourceType: 'BANK' },
      responseType: 'blob',
      timeout: 30000,
      headers: { Authorization: getToken() ? 'Bearer ' + getToken() : '' }
    })
    const blob = new Blob([res.data], {
      type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
    })
    const link = document.createElement('a')
    link.href = URL.createObjectURL(blob)
    link.download = '银行流水导入模板.xlsx'
    link.click()
    URL.revokeObjectURL(link.href)
    ElMessage.success('模板已下载')
  } catch (e) {
    let msg = '下载模板失败'
    if (e.response?.data instanceof Blob) {
      try {
        const text = await e.response.data.text()
        const obj = JSON.parse(text)
        msg = obj.msg || msg
      } catch (_) {}
    }
    ElMessage.error(msg)
  }
}

// 自定义上传：调用银行流水导入接口
async function customUpload({ file }) {
  uploading.value = true
  const formData = new FormData()
  formData.append('file', file)
  try {
    const res = await request({
      url: '/data/import/bank',
      method: 'post',
      data: formData,
      headers: { 'Content-Type': 'multipart/form-data' }
    })
    const result = res.data || {}
    if (result.wholeTableDuplicate) {
      await ElMessageBox.alert(
        `该文件中的 ${result.duplicateCount} 条订单号在系统中均已存在，未导入任何新数据。\n\n可能原因：该文件已经导入过。\n\n如需重新导入，请先在「数据管理」中删除对应记录。`,
        '该表已导入过',
        { type: 'warning', confirmButtonText: '我知道了' }
      )
    } else if (result.failedCount > 0) {
      // 有失败行：弹出错误明细窗口
      ElMessage.warning(`导入完成：成功 ${result.successCount} 条，失败 ${result.failedCount} 条，请查看错误明细`)
      if (result.batchNo) {
        await handleViewErrors({ batchNo: result.batchNo })
      }
    } else {
      ElMessage.success(`导入成功：共 ${result.successCount} 条`)
    }
    loadData()
  } catch (e) {
    // 错误已由请求拦截器统一处理
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

// 查看批次错误明细
async function handleViewErrors(row) {
  try {
    const res = await request({
      url: `/data/import/batch/${row.batchNo}/errors`,
      method: 'get'
    })
    const batch = res.data
    if (batch && batch.errorDetail) {
      try {
        batch.parsedErrors = JSON.parse(batch.errorDetail)
      } catch (_) {
        batch.parsedErrors = []
      }
    } else {
      batch.parsedErrors = []
    }
    errorBatch.value = batch
    errorDialogVisible.value = true
  } catch (e) {
    // 忽略
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

// 页面状态持久化：切换页面后再切回时，自动恢复上次的页号
watch(page, v => saveField('page', { ...v }), { deep: true })

onMounted(() => {
  loadData()
})
</script>

<template>
  <div class="page-card">
    <!-- 说明区 -->
    <el-alert
      type="info"
      :closable="false"
      style="margin-bottom: 16px"
    >
      <template #title>
        <span>通过 Excel/CSV 文件批量导入银行流水（中国银行 / 招商银行 / 工商银行 / 建设银行等）。</span>
      </template>
      <div style="font-size: 12px; color: #909399; margin-top: 4px; line-height: 1.6">
        支持表头命名（自动识别）：交易流水号 / orderNo、银行 / platform（中国银行/招商银行/工商银行/建设银行）、交易金额 / amount、币种 / currency（默认 CNY）、交易时间 / orderTime、入账时间 / settleTime<br/>
        支持文件格式：.xls / .xlsx / .csv；同流水号重复导入将自动跳过；导入后需点击「清洗」生成结算金额；对账请在「数据管理」的对账模式中完成。
      </div>
    </el-alert>

    <!-- 下载模板按钮 -->
    <div style="margin-bottom: 12px">
      <el-button type="primary" plain @click="downloadTemplate">下载模板</el-button>
    </div>

    <!-- 上传区：拖拽上传 Excel/CSV -->
    <div class="mb-16" v-loading="uploading">
      <el-upload
        drag
        action=""
        :http-request="customUpload"
        :show-file-list="false"
        accept=".xls,.xlsx,.csv"
      >
        <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
        <div class="el-upload__text">将银行流水 Excel/CSV 文件拖到此处，或<em>点击上传</em></div>
        <template #tip>
          <div class="el-upload__tip">支持 .xls / .xlsx / .csv 文件，上传后自动按默认模板解析入库</div>
        </template>
      </el-upload>
    </div>

    <!-- 导入记录表格 -->
    <el-table :data="tableData" v-loading="loading" border stripe style="width: 100%">
      <el-table-column prop="batchNo" label="批次号" min-width="160" />
      <el-table-column prop="fileName" label="文件名" min-width="180" show-overflow-tooltip />
      <el-table-column prop="sourceType" label="来源" width="100" />
      <el-table-column prop="totalCount" label="总数" width="80" />
      <el-table-column prop="successCount" label="成功" width="80" />
      <el-table-column prop="failedCount" label="失败" width="80">
        <template #default="{ row }">
          <span :style="row.failedCount > 0 ? 'color: #f56c6c' : ''">{{ row.failedCount || 0 }}</span>
        </template>
      </el-table-column>
      <el-table-column label="导入时间" width="170">
        <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag :type="statusType(row.status)">{{ statusText(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="handleClean(row)">清洗</el-button>
          <el-button link type="warning" @click="handleViewErrors(row)">错误明细</el-button>
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

    <!-- 错误明细对话框 -->
    <el-dialog v-model="errorDialogVisible" title="批次错误明细" width="980px">
      <div v-if="errorBatch">
        <el-descriptions :column="2" border style="margin-bottom: 16px">
          <el-descriptions-item label="批次号">{{ errorBatch.batchNo }}</el-descriptions-item>
          <el-descriptions-item label="文件名">{{ errorBatch.fileName }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ errorBatch.status }}</el-descriptions-item>
          <el-descriptions-item label="总数/成功/失败">
            {{ errorBatch.totalCount || 0 }} / {{ errorBatch.successCount || 0 }} / {{ errorBatch.failedCount || 0 }}
          </el-descriptions-item>
          <el-descriptions-item v-if="errorBatch.errorMsg" label="批次错误" :span="2">
            <el-text type="danger">{{ errorBatch.errorMsg }}</el-text>
          </el-descriptions-item>
        </el-descriptions>
        <div v-if="errorBatch.parsedErrors && errorBatch.parsedErrors.length > 0" style="margin-top: 16px">
          <el-alert
            type="warning"
            :closable="false"
            style="margin-bottom: 12px"
            title="以下行导入或清洗失败，请按「修复建议」修正数据后重新导入；其他成功行不受影响。"
          />
          <div style="margin-bottom: 8px; font-weight: 600">
            行级错误明细（共 {{ errorBatch.parsedErrors.length }} 条，按行号排序）：
          </div>
          <el-table :data="errorBatch.parsedErrors" border stripe max-height="420" size="small">
            <el-table-column label="行号" width="70" align="center">
              <template #default="{ row }">{{ row.rowNo ?? '-' }}</template>
            </el-table-column>
            <el-table-column label="出错字段" width="120">
              <template #default="{ row }">
                <el-tag v-if="row.fieldName" type="danger" size="small">{{ row.fieldName }}</el-tag>
                <el-tag v-else-if="row.orderNo" type="warning" size="small">订单号: {{ row.orderNo }}</el-tag>
                <span v-else style="color: #909399">（未知）</span>
              </template>
            </el-table-column>
            <el-table-column label="原值" width="160" show-overflow-tooltip>
              <template #default="{ row }">
                <span v-if="row.fieldValue" style="color: #f56c6c; font-family: monospace">{{ row.fieldValue }}</span>
                <span v-else-if="row.rawLine" style="color: #f56c6c; font-family: monospace">{{ row.rawLine }}</span>
                <span v-else style="color: #909399">（空）</span>
              </template>
            </el-table-column>
            <el-table-column label="失败原因" min-width="220" show-overflow-tooltip>
              <template #default="{ row }">
                <span>{{ row.reason }}</span>
              </template>
            </el-table-column>
            <el-table-column label="修复建议" min-width="300" show-overflow-tooltip>
              <template #default="{ row }">
                <span v-if="row.suggestion" style="color: #409eff">{{ row.suggestion }}</span>
                <span v-else style="color: #909399">请检查该行数据是否符合模板要求，或点击「下载模板」获取标准格式</span>
              </template>
            </el-table-column>
          </el-table>
        </div>
        <el-empty v-else description="无行级错误明细" :image-size="60" />
      </div>
      <template #footer>
        <el-button type="primary" @click="errorDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
</style>
