// 核算模块接口：利润计算、分摊规则配置
import request from '@/utils/request'

/* ============ 利润核算 ============ */
export function calculateProfit(period) {
  return request({
    url: '/accounting/profit/calculate',
    method: 'post',
    params: { period }
  })
}

export function pageProfitReport(params) {
  return request({
    url: '/accounting/profit/report',
    method: 'get',
    params
  })
}

/* ============ 分摊规则配置 ============ */
export function pageRule(params) {
  return request({
    url: '/accounting/model/page',
    method: 'get',
    params
  })
}

export function createRule(data) {
  return request({
    url: '/accounting/model',
    method: 'post',
    data
  })
}

export function updateRule(data) {
  return request({
    url: '/accounting/model',
    method: 'put',
    data
  })
}

export function deleteRule(id) {
  return request({
    url: `/accounting/model/${id}`,
    method: 'delete'
  })
}

export function toggleRuleEnabled(id, enabled) {
  return request({
    url: `/accounting/model/${id}/enabled`,
    method: 'put',
    params: { enabled }
  })
}
