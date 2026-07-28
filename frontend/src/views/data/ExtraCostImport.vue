<script setup>
// 额外费用导入页：仅负责导入相关功能（上传、下载模板、导入记录、错误明细）
// 额外费用的业务操作（查看、编辑、删除）请在「数据管理」页面完成
import { reactive, ref, onMounted, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import axios from 'axios'
import request from '@/utils/request'
import { getToken } from '@/utils/auth'
import { usePageState } from '@/composables/usePageState'

// 页面状态保持（页号切换页面后自动恢复）
const { loadField, saveField } = usePageState('ExtraCostImport')

// 导入记录表格（查询 import_batch 表，sourceType=COST）
const tableData = ref([])
const total = ref(0)
const loading = ref(false)
const uploading = ref(false)
const page = reactive(loadField('page', { page: 1, size: 10 }))

// 错误明细对话框
const errorDialogVisible = ref(false)
const errorBatch = ref(null)

// 清洗结果对话框
const cleanDialogVisible = ref(false)
const cleanBatch = ref(null)

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
  return 'info' // IMPORTED
}

function statusText(status) {
  return status || 'IMPORTED'
}

// 加载额外费用导入记录（仅 COST 来源）
async function loadData() {
  loading.value = true
  try {
    const res = await request({
      url: '/data/import/bill/page',
      method: 'get',
      params: { ...page, sourceType: 'COST' }
    })
    tableData.value = res.data?.records || []
    total.value = res.data?.total || 0
  } catch (e) {
    // 错误已由请求拦截器统一处理
  } finally {
    loading.value = false
  }
}

// 下载额外费用默认模板
async function downloadTemplate() {
  try {
    const res = await axios.get('/api/data/import/template/download', {
      params: { sourceType: 'COST' },
      responseType: 'blob',
      timeout: 30000,
      headers: { Authorization: getToken() ? 'Bearer ' + getToken() : '' }
    })
    const blob = new Blob([res.data], {
      type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
    })
    const link = document.createElement('a')
    link.href = URL.createObjectURL(blob)
    link.download = '额外费用导入模板.xlsx'
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

// 自定义上传：调用额外费用导入接口
async function customUpload({ file }) {
  uploading.value = true
  const formData = new FormData()
  formData.append('file', file)
  try {
    const res = await request({
      url: '/data/import/cost',
      method: 'post',
      data: formData,
      headers: { 'Content-Type': 'multipart/form-data' },
      timeout: 60000
    })
    const result = res.data || {}
    if (result.failedCount > 0) {
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

// 解析清洗结果汇总 JSON
function parseCleanSummary(cleanSummaryStr) {
  if (!cleanSummaryStr) return null
  try {
    return JSON.parse(cleanSummaryStr)
  } catch (_) {
    return null
  }
}

// 查看清洗结果
async function handleViewCleanResult(row) {
  try {
    const res = await request({
      url: `/data/import/batch/${row.batchNo}/errors`,
      method: 'get'
    })
    const batch = res.data
    batch.parsedCleanSummary = parseCleanSummary(batch.cleanSummary)
    cleanBatch.value = batch
    cleanDialogVisible.value = true
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
        <span>通过 Excel/CSV 文件批量导入额外费用（物流费、广告费、仓储费、关税等），导入后自动参与利润核算。</span>
      </template>
      <div style="font-size: 12px; color: #909399; margin-top: 4px; line-height: 1.6">
        支持表头命名（自动识别）：费用类型 / costType、金额 / amount、币种 / currency（默认 CNY）、核算周期 / period（如 202607）、订单号 / orderNo（可选）、收款方 / payee、费用日期 / costDate、备注 / remark<br/>
        费用类型列支持中文（物流费/仓储费/广告费/关税税费/平台佣金/汇兑损失/退货损失/手续费/中转手续费/包装费/其他）或英文编码（LOGISTICS/WAREHOUSE/ADVERTISING 等）<br/>
        <b>分摊规则：</b>填了订单号的费用直接计入该订单成本；未填订单号的费用按订单金额占比分摊到周期内所有订单。
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
        <div class="el-upload__text">将额外费用 Excel/CSV 文件拖到此处，或<em>点击上传</em></div>
        <template #tip>
          <div class="el-upload__tip">支持 .xls / .xlsx / .csv 文件，上传后自动解析、折算 CNY 并入库</div>
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
      <el-table-column label="操作" width="170" fixed="right">
        <template #default="{ row }">
          <!-- CLEANED：已清洗，点击查看清洗结果 -->
          <el-button v-if="row.status === 'CLEANED'" link type="success" @click="handleViewCleanResult(row)">已清洗</el-button>
          <!-- CLEANING：清洗中，不可点击 -->
          <el-button v-else-if="row.status === 'CLEANING'" link type="info" loading disabled>清洗中...</el-button>
          <!-- FAILED：清洗失败，点击查看错误信息 -->
          <el-button v-else-if="row.status === 'FAILED'" link type="danger" @click="handleViewCleanResult(row)">清洗失败</el-button>
          <!-- 有失败行时额外显示错误明细按钮 -->
          <el-button v-if="row.failedCount > 0" link type="warning" @click="handleViewErrors(row)">错误明细</el-button>
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
            title="以下行导入失败，请按「修复建议」修正数据后重新导入；其他成功行不受影响。"
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
                <span v-else style="color: #909399">（未知）</span>
              </template>
            </el-table-column>
            <el-table-column label="原值" width="160" show-overflow-tooltip>
              <template #default="{ row }">
                <span v-if="row.fieldValue" style="color: #f56c6c; font-family: monospace">{{ row.fieldValue }}</span>
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

    <!-- 清洗结果对话框 -->
    <el-dialog v-model="cleanDialogVisible" title="清洗结果" width="980px">
      <div v-if="cleanBatch">
        <!-- 批次信息 -->
        <el-descriptions :column="2" border style="margin-bottom: 16px">
          <el-descriptions-item label="批次号">{{ cleanBatch.batchNo }}</el-descriptions-item>
          <el-descriptions-item label="文件名">{{ cleanBatch.fileName }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="statusType(cleanBatch.status)">{{ statusText(cleanBatch.status) }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="总数/成功/失败">
            {{ cleanBatch.totalCount || 0 }} / {{ cleanBatch.successCount || 0 }} / {{ cleanBatch.failedCount || 0 }}
          </el-descriptions-item>
          <el-descriptions-item v-if="cleanBatch.errorMsg" label="错误信息" :span="2">
            <el-text type="danger">{{ cleanBatch.errorMsg }}</el-text>
          </el-descriptions-item>
        </el-descriptions>

        <!-- 清洗汇总 -->
        <div v-if="cleanBatch.parsedCleanSummary">
          <div style="margin-bottom: 8px; font-weight: 600">清洗规则触发汇总：</div>
          <div style="margin-bottom: 16px">
            <el-tag
              v-for="item in (cleanBatch.parsedCleanSummary.summary || [])"
              :key="item.ruleName"
              type="success"
              style="margin: 0 8px 8px 0"
            >
              {{ item.displayName }} {{ item.count }} 条
            </el-tag>
          </div>
          <!-- 清洗动作明细表格 -->
          <div v-if="cleanBatch.parsedCleanSummary.details && cleanBatch.parsedCleanSummary.details.length > 0">
            <div style="margin-bottom: 8px; font-weight: 600">清洗动作明细（共 {{ cleanBatch.parsedCleanSummary.details.length }} 条）：</div>
            <el-table :data="cleanBatch.parsedCleanSummary.details" border stripe max-height="420" size="small">
              <el-table-column label="规则名称" width="160">
                <template #default="{ row }">{{ row.displayName }}</template>
              </el-table-column>
              <el-table-column label="动作描述" min-width="500" show-overflow-tooltip>
                <template #default="{ row }">{{ row.description }}</template>
              </el-table-column>
            </el-table>
          </div>
        </div>
        <el-empty v-else-if="cleanBatch.status !== 'FAILED'" description="本次清洗无特殊动作（格式统一已完成）" :image-size="60" />
      </div>
      <template #footer>
        <el-button type="primary" @click="cleanDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
</style>
