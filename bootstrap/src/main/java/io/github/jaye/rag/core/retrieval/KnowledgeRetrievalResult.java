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

package io.github.jaye.ai.askora.rag.core.retrieval;

import cn.hutool.core.util.StrUtil;
import io.github.jaye.ai.askora.framework.convention.RetrievedChunk;
import io.github.jaye.ai.askora.framework.convention.RetrievedChunkKey;
import io.github.jaye.ai.askora.rag.core.intent.IntentNode;
import io.github.jaye.ai.askora.rag.core.intent.NodeScore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 知识检索结果：chunk 列表 + chunk 到意图的归属关系
 * <p>
 * 归属关系由 {@code MultiChannelRetrievalEngine} 按定向作用域推导（见其 {@code deriveAttribution}），
 * 供下游做意图维度分组与意图命中资格判定
 */
public record KnowledgeRetrievalResult(List<RetrievedChunk> chunks,
                                       Map<String, Set<String>> intentIdsByChunkKey,
                                       Set<String> directedIntentIds) {

    /**
     * 紧凑构造器：对三个入参做空值防御，统一归一为不可变集合
     */
    public KnowledgeRetrievalResult {
        chunks = chunks == null ? List.of() : chunks;
        intentIdsByChunkKey = intentIdsByChunkKey == null ? Map.of() : intentIdsByChunkKey;
        directedIntentIds = directedIntentIds == null
                ? Set.of()
                : Set.copyOf(directedIntentIds);
    }

    /**
     * 空结果（无 chunk、无归属）
     */
    public static KnowledgeRetrievalResult empty() {
        return new KnowledgeRetrievalResult(List.of(), Map.of(), Set.of());
    }

    /**
     * 有证据支撑（最终存活 chunk 归属）的意图集合
     */
    public Set<String> retrievedIntentIds() {
        Set<String> intentIds = new LinkedHashSet<>();
        intentIdsByChunkKey.values().stream()
                .filter(Objects::nonNull)
                .forEach(intentIds::addAll);
        return Collections.unmodifiableSet(intentIds);
    }

    /**
     * 过滤出有资格进入上下文的意图
     * <p>
     * 定向作用域命中的意图必须检索到证据才算数（防止意图路由命中了但知识库为空时仍占用上下文名额）；
     * 非定向意图不要求证据，直接放行
     */
    public Set<String> eligibleIntentIds(List<NodeScore> candidateIntents) {
        Set<String> retrievedIntentIds = retrievedIntentIds();
        return candidateIntents.stream()
                .filter(Objects::nonNull)
                .map(NodeScore::getNode)
                .filter(Objects::nonNull)
                .map(IntentNode::getId)
                .filter(StrUtil::isNotBlank)
                .filter(intentId -> !directedIntentIds.contains(intentId)
                        || retrievedIntentIds.contains(intentId))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * 按 chunk 归属的意图分组：一个 chunk 可归属多个意图（多归属时在每个意图下都出现一次）
     * <p>
     * 无归属的 chunk（全局作用域或补充路证据）统一落到传入的 {@code globalKey} 桶
     */
    public Map<String, List<RetrievedChunk>> groupByIntent(String globalKey) {
        Map<String, List<RetrievedChunk>> grouped = new LinkedHashMap<>();
        for (RetrievedChunk chunk : chunks) {
            Set<String> intentIds = intentIdsByChunkKey.get(RetrievedChunkKey.of(chunk));
            if (intentIds == null || intentIds.isEmpty()) {
                grouped.computeIfAbsent(globalKey, ignored -> new ArrayList<>()).add(chunk);
                continue;
            }
            for (String intentId : intentIds) {
                grouped.computeIfAbsent(intentId, ignored -> new ArrayList<>()).add(chunk);
            }
        }
        return grouped;
    }
}
