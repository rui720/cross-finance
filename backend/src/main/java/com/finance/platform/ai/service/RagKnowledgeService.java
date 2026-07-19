package com.finance.platform.ai.service;

import java.util.List;

/**
 * RAG 检索服务接口
 * <p>
 * 提供知识库语义检索与文档入库能力，为 AI 分析补充外部知识上下文。
 */
public interface RagKnowledgeService {

    /**
     * 语义检索相关知识片段
     *
     * @param query 查询文本
     * @param topK  返回条数
     * @return 知识片段列表
     */
    List<String> search(String query, int topK);

    /**
     * 知识入库
     *
     * @param docId   文档 ID
     * @param content 文档内容
     */
    void ingest(String docId, String content);
}
