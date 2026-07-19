package com.finance.platform.ai.service.impl;

import com.finance.platform.ai.service.RagKnowledgeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * RAG 知识检索实现
 * <p>
 * 向量库尚未对接，search 暂返回空列表、ingest 暂为空操作，保证 AI 流程可降级运行。
 * 后续接入 Chroma 向量库后替换为真实的语义检索与入库逻辑。
 */
@Slf4j
@Service
public class RagKnowledgeServiceImpl implements RagKnowledgeService {

    @Override
    public List<String> search(String query, int topK) {
        // TODO: 后续对接 Chroma 向量库，对 query 做向量化并召回 topK 知识片段
        log.warn("[RAG] 向量库未配置，search 返回空列表 query={}", query);
        return Collections.emptyList();
    }

    @Override
    public void ingest(String docId, String content) {
        // TODO: 后续对接 Chroma 向量库，将文档切片并向量化入库
        log.warn("[RAG] 向量库未配置，ingest 暂为空操作 docId={}", docId);
    }
}
