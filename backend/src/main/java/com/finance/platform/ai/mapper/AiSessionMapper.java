package com.finance.platform.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.finance.platform.ai.entity.AiSession;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 会话数据访问层
 */
@Mapper
public interface AiSessionMapper extends BaseMapper<AiSession> {
}
