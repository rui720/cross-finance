package com.finance.platform.system.service;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finance.platform.common.exception.BusinessException;
import com.finance.platform.data.entity.ExchangeRateSnapshot;
import com.finance.platform.data.entity.ExtraCost;
import com.finance.platform.data.entity.RawOrder;
import com.finance.platform.data.mapper.ExchangeRateSnapshotMapper;
import com.finance.platform.data.mapper.ExtraCostMapper;
import com.finance.platform.data.mapper.RawOrderMapper;
import com.finance.platform.system.entity.SysAuditLog;
import com.finance.platform.system.entity.SysUser;
import com.finance.platform.system.mapper.SysAuditLogMapper;
import com.finance.platform.system.mapper.SysUserMapper;
import com.finance.platform.system.mapper.UndoMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 审计日志撤销服务
 * <p>
 * 根据审计日志记录的操作类型和旧值快照，执行对应的撤销逻辑：
 * <ul>
 *   <li>update/状态变更类操作：从 old_value 恢复旧实体（updateById）</li>
 *   <li>delete/batchDelete 类操作：恢复 deleted=0（UndoMapper 原始 SQL）</li>
 *   <li>add/create 类操作：逻辑删除新增的记录（deleted=0 → deleted=1）</li>
 *   <li>import 类操作：按批次号逻辑删除导入数据</li>
 *   <li>calculate 类操作：按周期逻辑删除利润报表</li>
 * </ul>
 * 撤销成功后将审计日志的 undone 标记为 1，防止重复撤销。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UndoService {

    private final SysAuditLogMapper auditLogMapper;
    private final UndoMapper undoMapper;
    private final ApplicationContext applicationContext;
    private final ObjectMapper objectMapper;

    /** update/状态变更操作的元数据：mapper 类 + 实体类 + 是否批量 */
    private record UndoMeta(Class<? extends BaseMapper<?>> mapperClass, Class<?> entityClass, boolean batch) {}

    private static final Map<String, UndoMeta> UPDATE_OPS = Map.ofEntries(
            Map.entry("DataManagementController.updateOrder", new UndoMeta(RawOrderMapper.class, RawOrder.class, false)),
            Map.entry("DataManagementController.updateCost", new UndoMeta(ExtraCostMapper.class, ExtraCost.class, false)),
            Map.entry("DataManagementController.updateRate", new UndoMeta(ExchangeRateSnapshotMapper.class, ExchangeRateSnapshot.class, false)),
            Map.entry("SysUserController.update", new UndoMeta(SysUserMapper.class, SysUser.class, false)),
            Map.entry("SysUserController.resetPassword", new UndoMeta(SysUserMapper.class, SysUser.class, false)),
            Map.entry("SysUserController.assignRoles", new UndoMeta(SysUserMapper.class, SysUser.class, false)),
            Map.entry("BankFlowController.reconcile", new UndoMeta(RawOrderMapper.class, RawOrder.class, true)),
            Map.entry("BankFlowController.cancelReconcile", new UndoMeta(RawOrderMapper.class, RawOrder.class, true))
    );

    /**
     * 撤销指定审计日志对应的操作
     *
     * @param auditLogId 审计日志 ID
     * @return 撤销结果描述
     */
    @Transactional
    public String undo(Long auditLogId) {
        SysAuditLog auditLog = auditLogMapper.selectById(auditLogId);
        if (auditLog == null) {
            throw new BusinessException("审计日志不存在");
        }
        if (auditLog.getUndone() != null && auditLog.getUndone() == 1) {
            throw new BusinessException("该操作已撤销，不可重复撤销");
        }
        if (auditLog.getStatus() == null || auditLog.getStatus() == 0) {
            throw new BusinessException("原操作执行失败，不可撤销");
        }
        String oldValue = auditLog.getOldValue();
        if (oldValue == null || oldValue.isBlank()) {
            throw new BusinessException("该操作无旧值快照，无法撤销");
        }

        String key = extractKey(auditLog.getOperation());
        String desc = doUndo(key, oldValue);

        auditLog.setUndone(1);
        auditLogMapper.updateById(auditLog);
        log.info("[撤销] 审计日志 id={}, key={}, result={}", auditLogId, key, desc);
        return desc;
    }

    /** 从操作描述 "中文描述(ClassName.methodName)" 中提取 key */
    private String extractKey(String operation) {
        int start = operation.indexOf('(');
        int end = operation.lastIndexOf(')');
        if (start >= 0 && end > start) {
            return operation.substring(start + 1, end);
        }
        return operation;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private String doUndo(String key, String oldValue) {
        try {
            // 1. update/状态变更类操作：恢复旧实体
            UndoMeta meta = UPDATE_OPS.get(key);
            if (meta != null) {
                BaseMapper mapper = applicationContext.getBean(meta.mapperClass());
                if (meta.batch()) {
                    // 批量操作：old_value 是实体 JSON 数组
                    List<?> entities = objectMapper.readValue(oldValue,
                            objectMapper.getTypeFactory().constructCollectionType(List.class, meta.entityClass()));
                    for (Object entity : entities) {
                        mapper.updateById(entity);
                    }
                    return "已恢复 " + entities.size() + " 条记录的旧值";
                } else {
                    Object entity = objectMapper.readValue(oldValue, meta.entityClass());
                    mapper.updateById(entity);
                    return "已恢复记录旧值";
                }
            }

            // 2. delete 类操作：恢复 deleted=0
            switch (key) {
                case "SysUserController.delete" -> {
                    undoMapper.restoreUser(extractId(oldValue));
                    return "已恢复用户记录";
                }
                case "DataManagementController.deleteOrder" -> {
                    undoMapper.restoreOrder(extractId(oldValue));
                    return "已恢复账单记录";
                }
                case "DataManagementController.batchDeleteOrders" -> {
                    List<Long> ids = extractIds(oldValue);
                    ids.forEach(undoMapper::restoreOrder);
                    return "已恢复 " + ids.size() + " 条账单记录";
                }
                case "DataManagementController.deleteCost" -> {
                    undoMapper.restoreCost(extractId(oldValue));
                    return "已恢复费用记录";
                }
                case "DataManagementController.batchDeleteCosts" -> {
                    List<Long> ids = extractIds(oldValue);
                    ids.forEach(undoMapper::restoreCost);
                    return "已恢复 " + ids.size() + " 条费用记录";
                }
                case "DataManagementController.deleteRate" -> {
                    undoMapper.restoreRate(extractId(oldValue));
                    return "已恢复汇率记录";
                }
                case "DataManagementController.batchDeleteRates" -> {
                    List<Long> ids = extractIds(oldValue);
                    ids.forEach(undoMapper::restoreRate);
                    return "已恢复 " + ids.size() + " 条汇率记录";
                }
                case "SysAuditLogController.delete" -> {
                    undoMapper.restoreAuditLog(extractId(oldValue));
                    return "已恢复审计日志";
                }
                case "SysAuditLogController.batchDelete" -> {
                    List<Long> ids = extractIds(oldValue);
                    ids.forEach(undoMapper::restoreAuditLog);
                    return "已恢复 " + ids.size() + " 条审计日志";
                }
                default -> {
                    // 继续后续判断
                }
            }

            // 3. add/create 类操作：逻辑删除新增记录
            switch (key) {
                case "SysUserController.add" -> {
                    undoMapper.softDeleteUser(extractNewId(oldValue));
                    return "已撤销新增用户";
                }
                default -> {
                    // 继续后续判断
                }
            }

            // 4. import 类操作：按批次号删除
            if ("DataImportController.importBill".equals(key) || "DataImportController.importBankFlow".equals(key)) {
                String batchNo = extractBatchNo(oldValue);
                int rows = undoMapper.deleteByBatchNo(batchNo);
                return "已撤销导入，删除 " + rows + " 条账单记录";
            }

            // 5. calculate 类操作：按周期删除利润报表
            if ("ProfitCalcController.calculate".equals(key) || "ProfitCalcController.calculateByRange".equals(key)) {
                String period = extractPeriod(oldValue);
                int rows = undoMapper.deleteProfitReportByPeriod(period);
                return "已撤销核算，删除 " + rows + " 条利润报表";
            }

            // 6. recover 类操作：撤销用户恢复 = 重新逻辑删除该用户
            if ("SysUserController.recover".equals(key)) {
                Long userId = extractUserId(oldValue);
                undoMapper.softDeleteUser(userId);
                return "已撤销用户恢复（用户 " + userId + " 重新标记为已删除）";
            }

            throw new BusinessException("该操作不支持撤销：" + key);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("[撤销] 执行失败 key={}", key, e);
            throw new BusinessException("撤销失败：" + e.getMessage());
        }
    }

    // ==================== JSON 解析辅助方法 ====================

    private Long extractId(String json) throws Exception {
        JsonNode node = objectMapper.readTree(json);
        return node.get("id").asLong();
    }

    private List<Long> extractIds(String json) throws Exception {
        JsonNode node = objectMapper.readTree(json);
        List<Long> ids = new ArrayList<>();
        for (JsonNode idNode : node.get("ids")) {
            ids.add(idNode.asLong());
        }
        return ids;
    }

    private Long extractNewId(String json) throws Exception {
        JsonNode node = objectMapper.readTree(json);
        return node.get("newId").asLong();
    }

    private String extractBatchNo(String json) throws Exception {
        JsonNode node = objectMapper.readTree(json);
        return node.get("batchNo").asText();
    }

    private String extractPeriod(String json) throws Exception {
        JsonNode node = objectMapper.readTree(json);
        return node.get("period").asText();
    }

    private Long extractUserId(String json) throws Exception {
        JsonNode node = objectMapper.readTree(json);
        return node.get("userId").asLong();
    }
}
