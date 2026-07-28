// 系统管理接口：登录、用户、审计日志
import request from '@/utils/request'

/* ============ 登录/认证 ============ */
export function login(data) {
  return request({
    url: '/auth/login',
    method: 'post',
    data
  })
}

export function logout() {
  return request({
    url: '/auth/logout',
    method: 'post'
  })
}

/* ============ 用户管理 ============ */
export function pageUser(params) {
  return request({
    url: '/system/user/page',
    method: 'get',
    params
  })
}

export function createUser(data) {
  return request({
    url: '/system/user',
    method: 'post',
    data
  })
}

export function updateUser(data) {
  return request({
    url: '/system/user',
    method: 'put',
    data
  })
}

export function deleteUser(id) {
  return request({
    url: `/system/user/${id}`,
    method: 'delete'
  })
}

export function resetPassword(id, newPassword) {
  return request({
    url: `/system/user/${id}/reset-password`,
    method: 'put',
    data: { newPassword }
  })
}

// 分配角色：后端接收 List<String>（角色代码），前端直接发裸数组
export function assignRoles(id, roleCodes) {
  return request({
    url: `/system/user/${id}/roles`,
    method: 'put',
    data: roleCodes
  })
}

// 分页查询已逻辑删除的用户（用于恢复入口）
export function pageDeletedUser(params) {
  return request({
    url: '/system/user/deleted-page',
    method: 'get',
    params
  })
}

// 恢复已逻辑删除的用户
export function recoverUser(id) {
  return request({
    url: `/system/user/${id}/recover`,
    method: 'post'
  })
}

/* ============ 审计日志 ============ */
export function pageAuditLog(params) {
  return request({
    url: '/system/audit/page',
    method: 'get',
    params
  })
}

export function batchDeleteAuditLog(ids) {
  return request({
    url: '/system/audit/batch',
    method: 'delete',
    data: ids
  })
}

export function deleteAuditLog(id) {
  return request({
    url: `/system/audit/${id}`,
    method: 'delete'
  })
}

// 撤销审计日志对应的操作
export function undoAuditLog(id) {
  return request({
    url: `/system/audit/undo/${id}`,
    method: 'post'
  })
}
