// 资金风控接口：付款申请、审批
import request from '@/utils/request'

/* ============ 付款申请 ============ */
export function pagePaymentApply(params) {
  return request({
    url: '/fund/payment/page',
    method: 'get',
    params
  })
}

export function getPaymentApply(id) {
  return request({
    url: `/fund/payment/${id}`,
    method: 'get'
  })
}

export function submitPaymentApply(data) {
  return request({
    url: '/fund/payment/apply',
    method: 'post',
    data
  })
}

/* ============ 审批 ============ */
export function approvePayment(id) {
  return request({
    url: `/fund/approval/approve/${id}`,
    method: 'post'
  })
}

export function rejectPayment(id, reason) {
  return request({
    url: `/fund/approval/reject/${id}`,
    method: 'post',
    params: { reason }
  })
}

export function markPaid(id) {
  return request({
    url: `/fund/approval/mark-paid/${id}`,
    method: 'post'
  })
}

/* ============ 预算计划 ============ */
export function listBudgetPlan(params) {
  return request({
    url: '/fund/budget/list',
    method: 'get',
    params
  })
}
