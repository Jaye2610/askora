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

package io.github.jaye.ai.askora.knowledge.sink;

import cn.hutool.crypto.SecureUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.jaye.ai.askora.core.chunk.model.EmbeddedChunk;
import io.github.jaye.ai.askora.core.ingest.DocumentRef;
import io.github.jaye.ai.askora.core.ingest.VectorTarget;
import io.github.jaye.ai.askora.core.ingest.sink.ChunkSink;
import io.github.jaye.ai.askora.framework.context.UserContext;
import io.github.jaye.ai.askora.infra.token.TokenCounterService;
import io.github.jaye.ai.askora.knowledge.dao.entity.KnowledgeChunkDO;
import io.github.jaye.ai.askora.knowledge.dao.mapper.KnowledgeChunkMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 关系库落点：写 {@code t_knowledge_chunk}，展示文本与向量文本一并落库
 * <p>
 * {@code embedding_text} 落库不是为了展示：它让换嵌入模型时可以直接重嵌入而不必重新解析（省掉版面
 * 解析与视觉模型的重复成本），也让人工编辑单块后能正确重算向量文本
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RelationalChunkSink implements ChunkSink {

    private final KnowledgeChunkMapper chunkMapper;
    private final TokenCounterService tokenCounterService;

    /**
     * 全量替换文档分块：先删旧分块，再将新分块连同内容哈希、Token 数、向量文本一并写入关系库
     */
    @Override
    public void replaceDocument(VectorTarget target, DocumentRef doc, List<EmbeddedChunk> chunks) {
        deleteDocument(target, doc);
        if (chunks.isEmpty()) {
            return;
        }
        String username = UserContext.getUsername();
        List<KnowledgeChunkDO> rows = new ArrayList<>(chunks.size());
        for (EmbeddedChunk chunk : chunks) {
            String content = chunk.content();
            rows.add(KnowledgeChunkDO.builder()
                    .id(chunk.chunkId())
                    .kbId(doc.kbId())
                    .docId(doc.docId())
                    .chunkIndex(chunk.index())
                    .content(content)
                    .contentHash(SecureUtil.sha256(content))
                    .charCount(content.length())
                    .tokenCount(StringUtils.hasText(content) ? tokenCounterService.countTokens(content) : 0)
                    .embeddingText(chunk.embeddingText())
                    .enabled(1)
                    .createdBy(username)
                    .updatedBy(username)
                    .build());
        }
        chunkMapper.insert(rows);
        log.debug("关系库块写入完成 docId={} 块数={}", doc.docId(), rows.size());
    }

    /**
     * 删除指定文档在关系库中的全部分块
     */
    @Override
    public void deleteDocument(VectorTarget target, DocumentRef doc) {
        chunkMapper.delete(new LambdaQueryWrapper<KnowledgeChunkDO>()
                .eq(KnowledgeChunkDO::getDocId, doc.docId()));
    }
}
