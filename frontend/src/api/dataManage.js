// 数据管理接口：账单/银行流水/额外费用/汇率的在线 CRUD
import request from '@/utils/request'

/* ============ 账单/银行流水 ============ */

export function pageOrder(params) {
  return request({
    url: '/data/manage/order/page',
    method: 'get',
    params
  })
}

/**
 * 获取平台/银行名称下拉选项（按 source 区分）
 * @param source 'PLATFORM' 账单平台 / 'BANK' 银行名称 / 不传 返回全部
 */
export function getOrderPlatforms(source) {
  return request({
    url: '/data/manage/order/platforms',
    method: 'get',
    params: source ? { source } : {}
  })
}

export function updateOrder(id, data) {
  return request({
    url: `/data/manage/order/${id}`,
    method: 'put',
    data
  })
}

export function deleteOrder(id) {
  return request({
    url: `/data/manage/order/${id}`,
    method: 'delete'
  })
}

export function batchDeleteOrders(ids) {
  return request({
    url: '/data/manage/order/batch-delete',
    method: 'post',
    data: ids
  })
}

export function addOrder(data) {
  return request({
    url: '/data/manage/order',
    method: 'post',
    data
  })
}

/**
 * 批量编辑账单/银行流水（合并批次号 / 修正平台/币种/店铺）
 * @param data { ids: [Long], batchNo?, platform?, currency?, shopId? } 留空字段不修改
 */
export function batchUpdateOrders(data) {
  return request({
    url: '/data/manage/order/batch-update',
    method: 'post',
    data
  })
}

/**
 * 导出账单/银行流水为 Excel（按当前筛选条件，最多 10000 条）
 */
export function exportOrders(params) {
  return request({
    url: '/data/manage/order/export',
    method: 'get',
    params,
    responseType: 'blob'
  })
}

/* ============ 额外费用 ============ */

export function pageCost(params) {
  return request({
    url: '/data/manage/cost/page',
    method: 'get',
    params
  })
}

export function updateCost(id, data) {
  return request({
    url: `/data/manage/cost/${id}`,
    method: 'put',
    data
  })
}

export function deleteCost(id) {
  return request({
    url: `/data/manage/cost/${id}`,
    method: 'delete'
  })
}

export function batchDeleteCosts(ids) {
  return request({
    url: '/data/manage/cost/batch-delete',
    method: 'post',
    data: ids
  })
}

export function addCost(data) {
  return request({
    url: '/data/manage/cost',
    method: 'post',
    data
  })
}

/**
 * 批量补零：对指定日期范围内缺失的额外费用日期插入金额为 0 的占位记录
 * @param startDate 起始日期 yyyy-MM-dd（含）
 * @param endDate   结束日期 yyyy-MM-dd（含）
 * @returns 实际补零的记录条数
 */
export function fillZeroCost(startDate, endDate) {
  return request({
    url: '/data/manage/cost/fill-zero',
    method: 'post',
    params: { startDate, endDate }
  })
}

/**
 * 批量编辑额外费用（合并批次号 / 修正周期/币种/订单号/收款方/状态）
 * @param data { ids: [Long], batchNo?, period?, currency?, orderNo?, payee?, status? }
 */
export function batchUpdateCosts(data) {
  return request({
    url: '/data/manage/cost/batch-update',
    method: 'post',
    data
  })
}

/**
 * 导出额外费用为 Excel
 */
export function exportCosts(params) {
  return request({
    url: '/data/manage/cost/export',
    method: 'get',
    params,
    responseType: 'blob'
  })
}

/* ============ 汇率 ============ */

export function pageRate(params) {
  return request({
    url: '/data/manage/rate/page',
    method: 'get',
    params
  })
}

export function updateRate(id, data) {
  return request({
    url: `/data/manage/rate/${id}`,
    method: 'put',
    data
  })
}

export function deleteRate(id) {
  return request({
    url: `/data/manage/rate/${id}`,
    method: 'delete'
  })
}

export function batchDeleteRates(ids) {
  return request({
    url: '/data/manage/rate/batch-delete',
    method: 'post',
    data: ids
  })
}

export function addRate(data) {
  return request({
    url: '/data/manage/rate',
    method: 'post',
    data
  })
}

/**
 * 批量编辑汇率（修正币对方向/来源标记）
 * @param data { ids: [Long], fromCurrency?, toCurrency?, source? }
 */
export function batchUpdateRates(data) {
  return request({
    url: '/data/manage/rate/batch-update',
    method: 'post',
    data
  })
}

/**
 * 导出汇率为 Excel
 */
export function exportRates(params) {
  return request({
    url: '/data/manage/rate/export',
    method: 'get',
    params,
    responseType: 'blob'
  })
}

/**
 * 导出已核算利润报表为 Excel（数据管理页快捷入口）
 * @param params { period?, startDate?, endDate? } period 与 startDate+endDate 二选一
 */
export function exportProfitReport(params) {
  return request({
    url: '/data/manage/profit/export',
    method: 'get',
    params,
    responseType: 'blob'
  })
}
