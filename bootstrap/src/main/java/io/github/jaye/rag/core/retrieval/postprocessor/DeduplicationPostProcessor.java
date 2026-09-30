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

package io.github.jaye.ai.askora.rag.core.retrieval.postprocessor;

import io.github.jaye.ai.askora.framework.convention.RetrievedChunk;
import io.github.jaye.ai.askora.framework.convention.RetrievedChunkKey;
import io.github.jaye.ai.askora.rag.core.retrieval.channel.SearchChannelResult;
import io.github.jaye.ai.askora.rag.core.retrieval.channel.SearchContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 去重后置处理器
 * <p>
 * 合并多个通道的结果并按 key 去重，同一 Chunk 多路命中时保留首次出现的实例
 * 不比较跨通道原始分数，最终名次由下游 RRF 融合赋分
 */
@Component
public class DeduplicationPostProcessor implements SearchResultPostProcessor {

    @Override
    public String getName() {
        return "Deduplication";
    }

    @Override
    public int getOrder() {
        return 1;
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        return true;
    }

    /**
     * 按通道原始名次序去重：多通道命中同一 Chunk 时保留首个实例（LinkedHashMap 维持出现顺序）
     * <p>
     * 注意入参 chunks 不参与本步骤——这里以各通道原始结果为源重建列表，
     * 保证去重发生在 RRF 融合之前、同一证据不会带着多份名次进入融合
     */
    @Override
    public List<RetrievedChunk> process(List<RetrievedChunk> chunks,
                                        List<SearchChannelResult> results,
                                        SearchContext context) {
        Map<String, RetrievedChunk> chunkMap = new LinkedHashMap<>();
        for (SearchChannelResult result : results) {
            for (RetrievedChunk chunk : result.getChunks()) {
                chunkMap.putIfAbsent(RetrievedChunkKey.of(chunk), chunk);
            }
        }
        return new ArrayList<>(chunkMap.values());
    }
}
