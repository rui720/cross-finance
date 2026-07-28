// 核算模块接口：利润计算、多维分析、趋势诊断
import request from '@/utils/request'
import axios from 'axios'
import { getToken } from '@/utils/auth'

/* ============ 利润核算 ============ */

/**
 * 按月份周期触发核算
 * @param period YYYYMM 字符串
 */
export function calculateProfit(period) {
  return request({
    url: '/accounting/profit/calculate',
    method: 'post',
    params: { period }
  })
}

/**
 * 按自定义日期范围触发核算（新）
 * @param startDate yyyy-MM-dd
 * @param endDate   yyyy-MM-dd
 */
export function calculateProfitByRange(startDate, endDate) {
  return request({
    url: '/accounting/profit/calculate-by-range',
    method: 'post',
    params: { startDate, endDate }
  })
}

/**
 * 数据完整性检查（核算前调用）
 * 返回缺失数据的连续日期区间与汇总
 * @param startDate yyyy-MM-dd
 * @param endDate   yyyy-MM-dd
 */
export function checkDataIntegrity(startDate, endDate) {
  return request({
    url: '/accounting/profit/check-data',
    method: 'get',
    params: { startDate, endDate }
  })
}

export function pageProfitReport(params) {
  return request({
    url: '/accounting/profit/report',
    method: 'get',
    params
  })
}

/**
 * 分页查询利润明细（聚合视图）
 * 以账单为主体，关联银行流水、中转费与利润核算结果，一行展示完整利润明细
 * @param params { period?, startDate?, endDate?, page, size }
 */
export function pageProfitDetail(params) {
  return request({
    url: '/accounting/profit/detail',
    method: 'get',
    params
  })
}

/**
 * 导出利润报表为 Excel
 * 使用 axios 直接调用以获取 Blob（绕过 request 拦截器的统一 code 判断）
 * @param params { period?, startDate?, endDate? }
 */
export function exportProfitReport(params) {
  return axios({
    url: '/api/accounting/profit/export',
    method: 'get',
    params,
    responseType: 'blob',
    headers: {
      Authorization: 'Bearer ' + (getToken() || '')
    }
  })
}

/**
 * 自动补全缺失日期的汇率
 * @param fromCurrency 源币种
 * @param toCurrency 目标币种（默认 CNY）
 * @param startDate 起始日期 yyyy-MM-dd
 * @param endDate 结束日期 yyyy-MM-dd
 */
export function autoFillExchangeRate(fromCurrency, toCurrency, startDate, endDate) {
  return request({
    url: '/data/import/exchange-rate/auto-fill',
    method: 'post',
    params: { fromCurrency, toCurrency, startDate, endDate }
  })
}

/**
 * 利润诊断：返回四维度诊断结果（结构/趋势/成本/行动点）
 * period 兼容 YYYYMM（月份模式）和 "startDate~endDate"（范围模式拼接）
 */
export function diagnoseProfit(period) {
  return request({
    url: '/accounting/profit/diagnosis',
    method: 'get',
    params: { period }
  })
}

/**
 * 多维聚合：按指定维度（platform / shop_id / currency）分组聚合利润数据
 * @param params { period?, startDate?, endDate?, dimension }
 */
export function aggregateProfit(params) {
  return request({
    url: '/accounting/profit/aggregate',
    method: 'get',
    params
  })
}

/**
 * 成本结构汇总：返回当期平台费 / 公共分摊 / 直接成本 / 总成本合计
 * @param params { period?, startDate?, endDate? }
 */
export function getCostStructure(params) {
  return request({
    url: '/accounting/profit/cost-structure',
    method: 'get',
    params
  })
}

/**
 * 对账汇总：账面利润 vs 实际到账金额对比数据
 * @param params { period?, startDate?, endDate? }
 */
export function getReconcileSummary(params) {
  return request({
    url: '/accounting/profit/reconcile-summary',
    method: 'get',
    params
  })
}

/**
 * 亏损订单 Top N 清单（按利润升序，亏损最多的排前）
 * @param params { period?, startDate?, endDate?, limit? }
 */
export function getLossOrders(params) {
  return request({
    url: '/accounting/profit/loss-orders',
    method: 'get',
    params
  })
}

/**
 * 按店铺汇总利润报表
 * @param params { period?, startDate?, endDate? }
 */
export function getShopSummary(params) {
  return request({
    url: '/accounting/profit/shop-summary',
    method: 'get',
    params
  })
}

/**
 * 利润趋势：近 N 个月（默认 12）月度营收/成本/利润折线 + 同比数据
 * 仅支持 MONTH 模式（period 为 YYYYMM），RANGE 模式返回空
 * @param params { period, months? }
 */
export function getProfitTrend(params) {
  return request({
    url: '/accounting/profit/trend',
    method: 'get',
    params
  })
}

/**
 * 经营驾驶舱聚合数据：当期摘要 + 平台结构 + 近12个月趋势 + 币种分布
 * 数据来源均为已核算入库的 profit_report，不触发重新核算
 * @param period YYYYMM
 */
export function getDashboard(period) {
  return request({
    url: '/accounting/profit/dashboard',
    method: 'get',
    params: { period }
  })
}
