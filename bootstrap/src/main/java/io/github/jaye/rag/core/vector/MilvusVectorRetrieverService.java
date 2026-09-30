/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.jaye.ai.askora.rag.core.vector;

import cn.hutool.core.util.StrUtil;
import io.github.jaye.ai.askora.rag.config.RAGDefaultProperties;
import io.github.jaye.ai.askora.rag.core.retrieval.RetrieveRequest;
import io.github.jaye.ai.askora.framework.convention.RetrievedChunk;
import io.github.jaye.ai.askora.infra.embedding.EmbeddingService;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.BaseVector;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.SearchResp;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Milvus 向量检索服务，负责查询文本的 embedding 生成与共享 collection 内的近邻检索
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rag.vector.type", havingValue = "milvus", matchIfMissing = true)
public class MilvusVectorRetrieverService implements VectorRetrieverService {

    private final EmbeddingService embeddingService;
    private final MilvusClientV2 milvusClient;
    private final RAGDefaultProperties ragDefaultProperties;

    /**
     * 检索与查询文本最相关的文档块
     */
    @Override
    public List<RetrievedChunk> retrieve(RetrieveRequest retrieveParam) {
        float[] norm = embedAndNormalize(retrieveParam.getQuery());
        return retrieveByVector(norm, retrieveParam);
    }

    /**
     * 基于已生成的向量执行近邻检索，支持按逻辑集合名过滤共享 collection
     */
    @Override
    public List<RetrievedChunk> retrieveByVector(float[] vector, RetrieveRequest retrieveParam) {
        // 单个或多个逻辑库都在共享物理 Collection 中一次过滤，topK 是整个过滤范围的总预算
        String filter = buildCollectionFilter(retrieveParam.getEffectiveCollectionNames());
        return searchShared(vector, filter, retrieveParam.getTopK());
    }

    /**
     * 将查询文本转为 embedding 并做 L2 归一化，保证与入库向量的度量一致
     */
    @Override
    public float[] embedAndNormalize(String query) {
        return normalize(toArray(embeddingService.embed(query)));
    }

    /**
     * 是否支持跨全部知识库的全局检索
     */
    @Override
    public boolean supportsGlobalRetrieval() {
        return true;
    }

    /**
     * 构造 collection_name 标量过滤表达式，支持单个等值或多个 in 匹配
     */
    private String buildCollectionFilter(List<String> collectionNames) {
        if (collectionNames == null || collectionNames.isEmpty()) {
            return null;
        }
        if (collectionNames.size() == 1) {
            return "collection_name == \"" + escapeFilterValue(collectionNames.get(0)) + "\"";
        }
        String inList = collectionNames.stream()
                .map(this::escapeFilterValue)
                .map(value -> "\"" + value + "\"")
                .collect(Collectors.joining(", "));
        return "collection_name in [" + inList + "]";
    }

    /**
     * 转义过滤值中的反斜杠与双引号，防止过滤表达式被注入破坏
     */
    private String escapeFilterValue(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * 在共享 collection 内执行一次向量检索
     *
     * @param filter 可选的标量过滤表达式（为空则不过滤，检索全共享库）
     */
    private List<RetrievedChunk> searchShared(float[] vector, String filter, int topK) {
        List<BaseVector> vectors = List.of(new FloatVec(vector));

        Map<String, Object> params = new HashMap<>();
        params.put("metric_type", ragDefaultProperties.getMetricType());
        params.put("ef", 128);

        var builder = SearchReq.builder()
                .collectionName(ragDefaultProperties.getCollectionName())
                .annsField("embedding")
                .data(vectors)
                .topK(topK)
                .searchParams(params)
                .outputFields(List.of("id", "content", "collection_name", "metadata"));
        if (StrUtil.isNotBlank(filter)) {
            builder.filter(filter);
        }

        SearchResp resp = milvusClient.search(builder.build());
        List<List<SearchResp.SearchResult>> results = resp.getSearchResults();

        if (results == null || results.isEmpty()) {
            return List.of();
        }

        return results.get(0).stream()
                .map(r -> RetrievedChunk.builder()
                        .id(Objects.toString(r.getEntity().get("id"), ""))
                        .text(Objects.toString(r.getEntity().get("content"), ""))
                        .collectionName(Objects.toString(r.getEntity().get("collection_name"), null))
                        .score(r.getScore())
                        .build())
                .collect(Collectors.toList());
    }

    /**
     * List&lt;Float&gt; 转原生 float 数组
     */
    private static float[] toArray(List<Float> list) {
        float[] arr = new float[list.size()];
        for (int i = 0; i < list.size(); i++) arr[i] = list.get(i);
        return arr;
    }

    /**
     * L2 归一化向量到单位长度，配合余弦/内积度量使用
     */
    private static float[] normalize(float[] v) {
        double sum = 0.0;
        for (float x : v) sum += x * x;
        double len = Math.sqrt(sum);
        float[] nv = new float[v.length];
        for (int i = 0; i < v.length; i++) nv[i] = (float) (v[i] / len);
        return nv;
    }
}
