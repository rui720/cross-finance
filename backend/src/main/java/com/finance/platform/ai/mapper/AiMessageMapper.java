package com.finance.platform.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.finance.platform.ai.entity.AiMessage;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 消息数据访问层
 */
@Mapper
public interface AiMessageMapper extends BaseMapper<AiMessage> {
}
