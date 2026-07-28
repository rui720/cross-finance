package com.finance.platform.common.aspect;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.finance.platform.common.core.Result;
import com.finance.platform.data.mapper.ExchangeRateSnapshotMapper;
import com.finance.platform.data.mapper.ExtraCostMapper;
import com.finance.platform.data.mapper.RawOrderMapper;
import com.finance.platform.system.entity.SysAuditLog;
import com.finance.platform.system.mapper.SysAuditLogMapper;
import com.finance.platform.system.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.context.ApplicationContext;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;

/**
 * 审计日志 AOP 切面
 * <p>
 * 拦截所有 Controller 方法，自动记录：操作人、操作描述、操作前快照、成败、错误信息。
 * 写入 sys_audit_log 表，支撑审计日志查询页与撤销功能。
 * <p>
 * 设计要点：
 * - 异常不阻断主流程（try-catch 包裹日志记录）
 * - 仅记录业务可读信息，不记录请求方法/参数/IP/耗时等技术性字段
 * - 已登录用户从 SecurityContext 提取 userId/username
 * - 未登录场景（如登录接口）从请求参数中提取尝试登录的用户名
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class AuditLogAspect {

    private final SysAuditLogMapper auditLogMapper;
    private final ApplicationContext applicationContext;
    // 使用 Spring 注入的 ObjectMapper（已注册 JavaTimeModule，支持 LocalDateTime 序列化），
    // 不能用 new ObjectMapper()，否则序列化含日期字段的实体会抛异常导致 oldValue 恒为空
    private final ObjectMapper objectMapper;

    /**
     * "类名.方法名" -> Mapper 类映射，用于在操作执行前查询旧值（支撑撤销）。
     * <p>
     * 仅对 update 和状态变更类操作记录旧值；删除类操作为逻辑删除，
     * 撤销时直接恢复 deleted=0 即可，无需记录旧值。
     */
    private static final Map<String, Class<? extends BaseMapper<?>>> OLD_VALUE_MAPPERS = Map.ofEntries(
            Map.entry("DataManagementController.updateOrder", RawOrderMapper.class),
            Map.entry("DataManagementController.updateCost", ExtraCostMapper.class),
            Map.entry("DataManagementController.updateRate", ExchangeRateSnapshotMapper.class),
            Map.entry("SysUserController.update", SysUserMapper.class),
            Map.entry("SysUserController.resetPassword", SysUserMapper.class),
            Map.entry("SysUserController.assignRoles", SysUserMapper.class),
            Map.entry("BankFlowController.reconcile", RawOrderMapper.class),
            Map.entry("BankFlowController.cancelReconcile", RawOrderMapper.class)
    );

    /**
     * 删除类操作 → Mapper 映射：撤销时恢复 deleted=0。
     * <p>
     * 在操作前查询实体完整信息存入 old_value，供详情弹窗展示被删除对象的具体信息。
     */
    private static final Map<String, Class<? extends BaseMapper<?>>> DELETE_MAPPERS = Map.ofEntries(
            Map.entry("SysUserController.delete", SysUserMapper.class),
            Map.entry("DataManagementController.deleteOrder", RawOrderMapper.class),
            Map.entry("DataManagementController.batchDeleteOrders", RawOrderMapper.class),
            Map.entry("DataManagementController.deleteCost", ExtraCostMapper.class),
            Map.entry("DataManagementController.batchDeleteCosts", ExtraCostMapper.class),
            Map.entry("DataManagementController.deleteRate", ExchangeRateSnapshotMapper.class),
            Map.entry("DataManagementController.batchDeleteRates", ExchangeRateSnapshotMapper.class),
            Map.entry("SysAuditLogController.delete", SysAuditLogMapper.class),
            Map.entry("SysAuditLogController.batchDelete", SysAuditLogMapper.class)
    );

    /**
     * "类名.方法名" -> 中文操作描述映射表
     * 外行人看中文描述，内行人看括号里的控制器定位
     */
    private static final Map<String, String> OPERATION_DESC = new HashMap<>();
    static {
        // 认证模块
        OPERATION_DESC.put("AuthController.login", "用户登录");
        OPERATION_DESC.put("AuthController.logout", "退出登录");
        // AuthController.refresh 不记录审计日志（高频自动刷新，无业务意义）
        // 用户管理
        OPERATION_DESC.put("SysUserController.page", "分页查询用户");
        OPERATION_DESC.put("SysUserController.add", "新增用户");
        OPERATION_DESC.put("SysUserController.update", "修改用户");
        OPERATION_DESC.put("SysUserController.delete", "删除用户");
        OPERATION_DESC.put("SysUserController.recover", "恢复已删除用户");
        OPERATION_DESC.put("SysUserController.resetPassword", "重置用户密码");
        OPERATION_DESC.put("SysUserController.assignRoles", "分配用户角色");
        // 审计日志
        OPERATION_DESC.put("SysAuditLogController.page", "分页查询审计日志");
        OPERATION_DESC.put("SysAuditLogController.batchDelete", "批量删除审计日志");
        OPERATION_DESC.put("SysAuditLogController.delete", "删除审计日志");
        // 数据导入
        OPERATION_DESC.put("DataImportController.billPage", "分页查询账单导入批次");
        OPERATION_DESC.put("DataImportController.importBill", "上传平台账单");
        OPERATION_DESC.put("DataImportController.importBankFlow", "上传银行流水");
        OPERATION_DESC.put("DataImportController.clean", "清洗导入数据");
        // 银行流水对账
        OPERATION_DESC.put("BankFlowController.page", "分页查询银行流水");
        OPERATION_DESC.put("BankFlowController.reconcile", "执行银行流水对账");
        OPERATION_DESC.put("BankFlowController.cancelReconcile", "取消银行流水对账");
        OPERATION_DESC.put("BankFlowController.reconcileStatus", "查询对账状态");
        // 利润核算
        OPERATION_DESC.put("ProfitCalcController.calculate", "执行利润核算");
        OPERATION_DESC.put("ProfitCalcController.calculateByRange", "按日期范围执行利润核算");
        OPERATION_DESC.put("ProfitCalcController.report", "查询利润报表");
        OPERATION_DESC.put("ProfitCalcController.exportReport", "导出利润报表");
        // AI 智能助手
        OPERATION_DESC.put("AiChatController.stream", "AI流式问答");
        OPERATION_DESC.put("AiChatController.ask", "AI智能问答");
        // 数据管理（在线 CRUD）—— 全部为高危写操作，必须审计
        OPERATION_DESC.put("DataManagementController.updateOrder", "编辑账单/银行流水记录");
        OPERATION_DESC.put("DataManagementController.deleteOrder", "删除账单/银行流水记录");
        OPERATION_DESC.put("DataManagementController.batchDeleteOrders", "批量删除账单/银行流水记录");
        OPERATION_DESC.put("DataManagementController.batchUpdateOrders", "批量编辑账单/银行流水记录");
        OPERATION_DESC.put("DataManagementController.updateCost", "编辑额外费用记录");
        OPERATION_DESC.put("DataManagementController.deleteCost", "删除额外费用记录");
        OPERATION_DESC.put("DataManagementController.batchDeleteCosts", "批量删除额外费用记录");
        OPERATION_DESC.put("DataManagementController.batchUpdateCosts", "批量编辑额外费用记录");
        OPERATION_DESC.put("DataManagementController.updateRate", "编辑汇率记录");
        OPERATION_DESC.put("DataManagementController.deleteRate", "删除汇率记录");
        OPERATION_DESC.put("DataManagementController.batchDeleteRates", "批量删除汇率记录");
        OPERATION_DESC.put("DataManagementController.batchUpdateRates", "批量编辑汇率记录");
    }

    /**
     * 需要记录审计日志的方法名白名单（动词前缀）
     * <p>
     * 设计原则：只记录"改变系统状态"和"涉及身份认证"的操作，
     * 排除查询类（page/get/list/count/report/detail/status/stream/ask/billPage），
     * 避免高频只读操作产生噪音日志。
     * <p>
     * 白名单方式更安全：新增方法时若忘记维护，默认不记录，不会污染日志。
     */
    private static final Set<String> AUDITED_METHODS = Set.of(
            // 认证（仅记录登录/登出，不记录刷新Token——刷新是高频自动操作，无业务意义）
            "login", "logout",
            // 增
            "add", "create", "importBill", "importBankFlow",
            // 删
            "delete", "batchDelete", "remove",
            // 改
            "update", "resetPassword", "assignRoles", "toggleEnabled",
            "enable", "disable",
            "clean", "reconcile", "cancelReconcile",
            // 数据管理（在线 CRUD）—— DataManagementController 的写操作
            "updateOrder", "deleteOrder", "batchDeleteOrders", "batchUpdateOrders",
            "updateCost", "deleteCost", "batchDeleteCosts", "batchUpdateCosts",
            "updateRate", "deleteRate", "batchDeleteRates", "batchUpdateRates",
            // 利润核算（导出报表）
            "exportReport",
            // 用户恢复（撤销逻辑删除）
            "recover"
    );

    /**
     * 切点：所有 controller 包下的方法
     */
    @Pointcut("execution(* com.finance.platform..controller.*Controller.*(..))")
    public void controllerPointcut() {
    }

    @Around("controllerPointcut()")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        String methodName = joinPoint.getSignature().getName();
        // 仅记录白名单内的方法（增删改 + 认证 + 恢复），跳过查询类操作
        if (!AUDITED_METHODS.contains(methodName)) {
            return joinPoint.proceed();
        }

        String className = joinPoint.getSignature().getDeclaringType().getSimpleName();
        String operationKey = className + "." + methodName;

        // 操作前采集旧值快照（用于撤销恢复）
        String oldValue = captureSnapshotBefore(joinPoint, operationKey);

        Object result;
        Throwable error = null;
        try {
            result = joinPoint.proceed();
            // 操作后补充采集（新增操作的 ID、导入操作的批次号）
            if (oldValue == null) {
                oldValue = captureSnapshotAfter(joinPoint, operationKey, result);
            }
            return result;
        } catch (Throwable t) {
            error = t;
            throw t;
        } finally {
            // 记录审计日志（异常不抛出，保证不影响主流程）
            try {
                recordAuditLog(joinPoint, error, oldValue);
            } catch (Exception e) {
                log.warn("[审计] 记录审计日志失败: {}", e.getMessage());
            }
        }
    }

    private void recordAuditLog(ProceedingJoinPoint joinPoint, Throwable error, String oldValue) {
        SysAuditLog auditLog = new SysAuditLog();
        auditLog.setOperation(buildOperation(joinPoint));
        auditLog.setOldValue(oldValue);
        auditLog.setStatus(error == null ? 1 : 0);
        auditLog.setErrorMsg(error == null ? null : truncate(error.getMessage(), 500));

        // 填充 userId / username
        fillUserInfo(auditLog, joinPoint);

        auditLogMapper.insert(auditLog);
    }

    /**
     * 操作前采集旧值快照（用于撤销恢复）。
     * <ul>
     *   <li>update/状态变更类操作（在 OLD_VALUE_MAPPERS 中）：查询完整实体旧值</li>
     *   <li>delete/batchDelete 类操作（在 DELETE_OPS 中）：记录被删除的 ID 列表</li>
     *   <li>calculate 类操作：记录核算周期</li>
     *   <li>其他操作：返回 null（不采集）</li>
     * </ul>
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private String captureSnapshotBefore(ProceedingJoinPoint joinPoint, String operationKey) {
        try {
            Object[] args = joinPoint.getArgs();
            if (args == null || args.length == 0) return null;
            Object firstArg = args[0];

            // 1. update/状态变更类操作：查询完整实体旧值
            Class<? extends BaseMapper<?>> mapperClass = OLD_VALUE_MAPPERS.get(operationKey);
            if (mapperClass != null) {
                BaseMapper mapper = applicationContext.getBean(mapperClass);
                if (firstArg instanceof List<?> list && !list.isEmpty()) {
                    // 批量操作（reconcile/cancelReconcile）：查询所有实体旧值
                    List<Long> ids = toLongList(list);
                    if (ids.isEmpty()) return null;
                    List<?> entities = mapper.selectBatchIds(ids);
                    return objectMapper.writeValueAsString(entities);
                }
                Long id = null;
                if (firstArg instanceof Long l) {
                    id = l;
                } else {
                    id = extractIdFromEntity(firstArg);
                }
                if (id == null) return null;
                Object entity = mapper.selectById(id);
                return entity == null ? null : objectMapper.writeValueAsString(entity);
            }

            // 2. delete/batchDelete 类操作：查询实体完整信息，连同 ID 一起存入快照
            Class<? extends BaseMapper<?>> deleteMapperClass = DELETE_MAPPERS.get(operationKey);
            if (deleteMapperClass != null) {
                BaseMapper mapper = applicationContext.getBean(deleteMapperClass);
                if (firstArg instanceof List<?> list && !list.isEmpty()) {
                    List<Long> ids = toLongList(list);
                    if (!ids.isEmpty()) {
                        List<?> entities = mapper.selectBatchIds(ids);
                        Map<String, Object> snapshot = new HashMap<>();
                        snapshot.put("ids", ids);
                        snapshot.put("entities", entities);
                        return objectMapper.writeValueAsString(snapshot);
                    }
                }
                if (firstArg instanceof Long id) {
                    Object entity = mapper.selectById(id);
                    Map<String, Object> snapshot = new HashMap<>();
                    snapshot.put("id", id);
                    if (entity != null) {
                        snapshot.put("entity", entity);
                    }
                    return objectMapper.writeValueAsString(snapshot);
                }
            }

            // 3. calculate 类操作：记录核算周期
            if ("ProfitCalcController.calculate".equals(operationKey) && firstArg instanceof String period) {
                return objectMapper.writeValueAsString(Map.of("period", period));
            }
            if ("ProfitCalcController.calculateByRange".equals(operationKey) && firstArg instanceof String startDate) {
                String endDate = args.length > 1 && args[1] instanceof String e ? e : null;
                return objectMapper.writeValueAsString(Map.of("period", startDate + "~" + endDate));
            }
            // 4. recover 类操作：记录被恢复的用户 ID（撤销 recover 时需要重新逻辑删除）
            if ("SysUserController.recover".equals(operationKey) && firstArg instanceof Long userId) {
                return objectMapper.writeValueAsString(Map.of("userId", userId));
            }
            return null;
        } catch (Exception e) {
            log.warn("[审计] 采集旧值失败 {}: {}", operationKey, e.getMessage());
            return null;
        }
    }

    /**
     * 操作后补充采集（用于新增/导入类操作）。
     * <ul>
     *   <li>新增类操作：从实体参数中提取新生成的 ID（MyBatis-Plus 插入后回填）</li>
     *   <li>导入类操作：从返回结果中提取批次号</li>
     * </ul>
     */
    private String captureSnapshotAfter(ProceedingJoinPoint joinPoint, String operationKey, Object result) {
        try {
            Object[] args = joinPoint.getArgs();
            if (args == null || args.length == 0) return null;

            // 新增类操作：提取新生成的 ID（MyBatis-Plus insert 后回填到实体）
            if ("SysUserController.add".equals(operationKey)) {
                Long newId = extractIdFromEntity(args[0]);
                if (newId != null) {
                    return objectMapper.writeValueAsString(Map.of("newId", newId));
                }
            }

            // 导入类操作：从返回结果中提取批次号和导入统计信息
            if ("DataImportController.importBill".equals(operationKey)
                    || "DataImportController.importBankFlow".equals(operationKey)) {
                Object data = unwrapResult(result);
                if (data != null) {
                    try {
                        Map<String, Object> snapshot = new HashMap<>();
                        // 提取批次号
                        var batchNoMethod = data.getClass().getMethod("batchNo");
                        Object batchNo = batchNoMethod.invoke(data);
                        if (batchNo instanceof String bn) {
                            snapshot.put("batchNo", bn);
                        }
                        // 提取总行数
                        try {
                            var totalMethod = data.getClass().getMethod("totalRows");
                            snapshot.put("totalRows", totalMethod.invoke(data));
                        } catch (NoSuchMethodException ignored) {}
                        // 提取成功数
                        try {
                            var successMethod = data.getClass().getMethod("successCount");
                            snapshot.put("successCount", successMethod.invoke(data));
                        } catch (NoSuchMethodException ignored) {}
                        // 提取失败数
                        try {
                            var failedMethod = data.getClass().getMethod("failedCount");
                            snapshot.put("failedCount", failedMethod.invoke(data));
                        } catch (NoSuchMethodException ignored) {}
                        // 提取重复数
                        try {
                            var dupMethod = data.getClass().getMethod("duplicateCount");
                            snapshot.put("duplicateCount", dupMethod.invoke(data));
                        } catch (NoSuchMethodException ignored) {}
                        // 提取整表重复标志
                        try {
                            var wtdMethod = data.getClass().getMethod("wholeTableDuplicate");
                            snapshot.put("wholeTableDuplicate", wtdMethod.invoke(data));
                        } catch (NoSuchMethodException ignored) {}
                        // 提取文件名（从方法参数 MultipartFile 中获取）
                        for (Object arg : args) {
                            if (arg instanceof MultipartFile mf) {
                                snapshot.put("fileName", mf.getOriginalFilename());
                                break;
                            }
                        }
                        if (!snapshot.isEmpty()) {
                            return objectMapper.writeValueAsString(snapshot);
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
            return null;
        } catch (Exception e) {
            log.warn("[审计] 采集新值失败 {}: {}", operationKey, e.getMessage());
            return null;
        }
    }

    /** 从 Result 中解包出 data */
    private Object unwrapResult(Object result) {
        if (result instanceof Result<?> r) {
            return r.getData();
        }
        return null;
    }

    /** 从实体参数中提取 ID（通过反射调用 getId()） */
    private Long extractIdFromEntity(Object entity) {
        if (entity == null) return null;
        try {
            var getIdMethod = entity.getClass().getMethod("getId");
            Object id = getIdMethod.invoke(entity);
            if (id instanceof Long l) return l;
            if (id instanceof Number n) return n.longValue();
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 将 List 转为 List<Long>（兼容 Integer 等数字类型） */
    private List<Long> toLongList(List<?> list) {
        List<Long> ids = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Long l) ids.add(l);
            else if (o instanceof Number n) ids.add(n.longValue());
        }
        return ids;
    }

    /**
     * 构建操作描述：中文描述(类名.方法名)
     * <p>
     * 外行人通过中文描述理解操作含义，内行人通过括号内的类名.方法名定位代码位置。
     * 未在映射表中的方法，仅显示类名.方法名。
     */
    private String buildOperation(ProceedingJoinPoint joinPoint) {
        String className = joinPoint.getSignature().getDeclaringType().getSimpleName();
        String methodName = joinPoint.getSignature().getName();
        String key = className + "." + methodName;
        String desc = OPERATION_DESC.get(key);
        return desc != null ? desc + "(" + key + ")" : key;
    }

    /**
     * 填充操作人信息：
     * 1. 已登录：从 SecurityContext 提取 LoginUser（userId + username）
     * 2. 未登录（如登录接口）：从方法参数中提取尝试登录的用户名
     */
    private void fillUserInfo(SysAuditLog auditLog, ProceedingJoinPoint joinPoint) {
        // 1. 尝试从 SecurityContext 获取已登录用户
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() != null
                && !"anonymousUser".equals(auth.getPrincipal().toString())) {
            Object principal = auth.getPrincipal();
            // JwtAuthenticationFilter 中 principal 是 LoginUser（持有 userId 与 username）
            if (principal instanceof com.finance.platform.common.core.LoginUser loginUser) {
                auditLog.setUserId(loginUser.userId());
                auditLog.setUsername(loginUser.username());
                return;
            }
            // 兼容旧逻辑：principal 是 userId（Long）
            if (principal instanceof Long id) {
                auditLog.setUserId(id);
                auditLog.setUsername("userId:" + id);
                return;
            }
        }

        // 2. 未登录场景：尝试从方法参数中提取用户名（如登录接口的 LoginDTO / Map）
        for (Object arg : joinPoint.getArgs()) {
            if (arg == null) continue;
            // record 类型（AuthController.LoginDTO）有 username 方法
            try {
                var usernameMethod = arg.getClass().getMethod("username");
                Object usernameVal = usernameMethod.invoke(arg);
                if (usernameVal instanceof String u && !u.isBlank()) {
                    auditLog.setUsername(u);
                    auditLog.setUserId(null); // 未登录，userId 为空
                    return;
                }
            } catch (NoSuchMethodException ignored) {
            } catch (Exception ignored) {
            }
            // Map 类型
            if (arg instanceof Map<?, ?> map) {
                Object usernameVal = map.get("username");
                if (usernameVal instanceof String u && !u.isBlank()) {
                    auditLog.setUsername(u);
                    auditLog.setUserId(null);
                    return;
                }
            }
        }
        // 无法获取用户名
        auditLog.setUserId(null);
        auditLog.setUsername("anonymous");
    }

    /** 截断字符串到指定长度（用于 errorMsg） */
    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
