package com.finance.platform.data.etl.rule;

import com.finance.platform.data.entity.RawOrder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 清洗规则责任链执行器
 * <p>
 * 根据 ImportTemplate.cleanRules 配置的规则 Bean 名列表，
 * 从 Spring 容器中查找对应的 CleanRule 实现，按顺序组成责任链。
 */
@Slf4j
@Component
public class CleanRuleChain {

    private final Map<String, CleanRule> ruleMap;

    public CleanRuleChain(List<CleanRule> rules) {
        this.ruleMap = new LinkedHashMap<>();
        for (CleanRule rule : rules) {
            this.ruleMap.put(rule.ruleName(), rule);
        }
        log.info("[CleanRuleChain] 已注册清洗规则：{}", ruleMap.keySet());
    }

    /**
     * 构建责任链
     *
     * @param ruleNames 逗号分隔的规则 Bean 名列表
     * @return 有序规则列表
     */
    public List<CleanRule> buildChain(String ruleNames) {
        List<CleanRule> chain = new ArrayList<>();
        if (ruleNames == null || ruleNames.isBlank()) {
            return chain;
        }
        for (String name : ruleNames.split(",")) {
            String trimmed = name.trim();
            CleanRule rule = ruleMap.get(trimmed);
            if (rule == null) {
                log.warn("[CleanRuleChain] 未找到规则：{}，跳过", trimmed);
                continue;
            }
            chain.add(rule);
        }
        return chain;
    }

    /**
     * 对单条记录依次应用责任链上的规则
     *
     * @return 第一个失败的规则结果；全部通过返回 ok
     */
    public CleanRule.CleanResult applyAll(List<CleanRule> chain, RawOrder order, CleanContext context) {
        for (CleanRule rule : chain) {
            CleanRule.CleanResult result = rule.apply(order, context);
            if (!result.ok()) {
                return result;
            }
        }
        return CleanRule.CleanResult.pass();
    }
}
