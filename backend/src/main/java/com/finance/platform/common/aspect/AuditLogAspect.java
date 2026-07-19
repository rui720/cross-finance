package com.finance.platform.common.aspect;

import com.finance.platform.system.entity.SysAuditLog;
import com.finance.platform.system.mapper.SysAuditLogMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 审计日志 AOP 切面
 * <p>
 * 拦截所有 Controller 方法，自动记录：操作人、请求方法、参数、IP、耗时、成败。
 * 写入 sys_audit_log 表，支撑审计日志查询页。
 * <p>
 * 设计要点：
 * - 异常不阻断主流程（try-catch 包裹日志记录）
 * - 文件/流类参数跳过序列化，避免 OOM
 * - 参数截断到 2000 字符，防止超长 SQL
 * - 已登录用户从 SecurityContext 提取 userId/username
 * - 未登录场景（如登录接口）从请求参数中提取尝试登录的用户名
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class AuditLogAspect {

    private final SysAuditLogMapper auditLogMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * "类名.方法名" -> 中文操作描述映射表
     * 外行人看中文描述，内行人看括号里的控制器定位
     */
    private static final Map<String, String> OPERATION_DESC = new HashMap<>();
    static {
        // 认证模块
        OPERATION_DESC.put("AuthController.login", "用户登录");
        OPERATION_DESC.put("AuthController.logout", "退出登录");
        // 用户管理
        OPERATION_DESC.put("SysUserController.page", "分页查询用户");
        OPERATION_DESC.put("SysUserController.add", "新增用户");
        OPERATION_DESC.put("SysUserController.update", "修改用户");
        OPERATION_DESC.put("SysUserController.delete", "删除用户");
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
        OPERATION_DESC.put("BankFlowController.reconcileStatus", "查询对账状态");
        // 分摊规则配置
        OPERATION_DESC.put("ModelConfigController.create", "新增分摊规则");
        OPERATION_DESC.put("ModelConfigController.update", "修改分摊规则");
        OPERATION_DESC.put("ModelConfigController.delete", "删除分摊规则");
        OPERATION_DESC.put("ModelConfigController.get", "查询分摊规则详情");
        OPERATION_DESC.put("ModelConfigController.page", "分页查询分摊规则");
        OPERATION_DESC.put("ModelConfigController.toggleEnabled", "切换分摊规则启用状态");
        OPERATION_DESC.put("ModelConfigController.enable", "启用分摊规则");
        OPERATION_DESC.put("ModelConfigController.disable", "禁用分摊规则");
        // 利润核算
        OPERATION_DESC.put("ProfitCalcController.calculate", "执行利润核算");
        OPERATION_DESC.put("ProfitCalcController.report", "查询利润报表");
        // 付款申请
        OPERATION_DESC.put("PaymentApplyController.apply", "提交付款申请");
        OPERATION_DESC.put("PaymentApplyController.page", "分页查询付款申请");
        OPERATION_DESC.put("PaymentApplyController.detail", "查询付款申请详情");
        // 付款审批
        OPERATION_DESC.put("ApprovalController.approve", "审批通过");
        OPERATION_DESC.put("ApprovalController.reject", "审批驳回");
        OPERATION_DESC.put("ApprovalController.markPaid", "标记已付款");
        // AI 智能助手
        OPERATION_DESC.put("AiChatController.stream", "AI流式问答");
        OPERATION_DESC.put("AiChatController.ask", "AI智能问答");
        OPERATION_DESC.put("AiReportController.profit", "AI利润归因分析");
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
            // 认证
            "login", "logout",
            // 增
            "add", "create", "apply", "importBill", "importBankFlow",
            // 删
            "delete", "batchDelete", "remove",
            // 改
            "update", "resetPassword", "assignRoles", "toggleEnabled",
            "enable", "disable", "approve", "reject", "markPaid",
            "clean", "reconcile"
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
        // 仅记录白名单内的方法（增删改 + 认证），跳过查询类操作
        if (!AUDITED_METHODS.contains(methodName)) {
            return joinPoint.proceed();
        }

        long start = System.currentTimeMillis();
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        HttpServletRequest request = attrs == null ? null : attrs.getRequest();

        Object result;
        Throwable error = null;
        try {
            result = joinPoint.proceed();
            return result;
        } catch (Throwable t) {
            error = t;
            throw t;
        } finally {
            // 记录审计日志（异常不抛出，保证不影响主流程）
            try {
                recordAuditLog(joinPoint, request, start, error);
            } catch (Exception e) {
                log.warn("[审计] 记录审计日志失败: {}", e.getMessage());
            }
        }
    }

    private void recordAuditLog(ProceedingJoinPoint joinPoint, HttpServletRequest request,
                                long start, Throwable error) {
        long cost = System.currentTimeMillis() - start;
        SysAuditLog auditLog = new SysAuditLog();
        auditLog.setOperation(buildOperation(joinPoint));
        auditLog.setMethod(request == null ? "UNKNOWN" : request.getMethod() + " " + request.getRequestURI());
        auditLog.setParams(truncateParams(joinPoint.getArgs()));
        auditLog.setIp(getClientIp(request));
        auditLog.setCostTime(cost);
        auditLog.setStatus(error == null ? 1 : 0);
        auditLog.setErrorMsg(error == null ? null : truncate(error.getMessage(), 500));

        // 填充 userId / username
        fillUserInfo(auditLog, joinPoint);

        auditLogMapper.insert(auditLog);
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

    /**
     * 参数序列化：跳过文件/流，截断到 2000 字符
     */
    private String truncateParams(Object[] args) {
        if (args == null || args.length == 0) {
            return null;
        }
        try {
            String json = objectMapper.writeValueAsString(Arrays.stream(args)
                    .filter(a -> !(a instanceof MultipartFile)
                            && !(a instanceof jakarta.servlet.ServletRequest)
                            && !(a instanceof jakarta.servlet.ServletResponse))
                    .collect(Collectors.toList()));
            return truncate(json, 2000);
        } catch (Exception e) {
            return "序列化失败: " + e.getMessage();
        }
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private String getClientIp(HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        return ip == null ? "unknown" : (ip.contains(",") ? ip.split(",")[0].trim() : ip);
    }
}
