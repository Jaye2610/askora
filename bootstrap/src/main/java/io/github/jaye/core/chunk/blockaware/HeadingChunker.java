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

package io.github.jaye.ai.askora.core.chunk.blockaware;

import io.github.jaye.ai.askora.core.chunk.model.ChunkDraft;
import io.github.jaye.ai.askora.core.chunk.model.ChunkMetadata;
import io.github.jaye.ai.askora.core.parser.model.HeadingBlock;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 标题 chunker：标题按原文位置回到正文
 * <p>
 * 标题不产块的话，{@code content} 就不是文档原貌而是被剥掉全部结构的裸正文，命中的块回填模型时也无从
 * 判断出自哪一节；井号数取原始级别，不按路径深度重算
 */
@Component
public class HeadingChunker implements BlockChunker<HeadingBlock> {

    /**
     * markdown 标题级别上限，超出按 6 级渲染
     */
    private static final int MAX_LEVEL = 6;

    /**
     * 本 chunker 处理的 Block 类型：标题
     */
    @Override
    public Class<HeadingBlock> blockType() {
        return HeadingBlock.class;
    }

    /**
     * 标题按原始级别渲染回 markdown 原文，落成带章节路径的草稿
     */
    @Override
    public List<ChunkDraft> chunk(HeadingBlock block, ChunkContext ctx) {
        if (block == null || !StringUtils.hasText(block.text())) {
            return List.of();
        }
        String text = block.text().strip();
        ChunkMetadata metadata = ChunkMetadata.builder()
                .outlinePath(ctx.outlinePath())
                .provenance(block.provenance())
                .build();

        int level = Math.min(MAX_LEVEL, Math.max(1, block.level()));
        // 向量文本不带井号，markdown 标记对嵌入模型是零信息 token
        return List.of(ChunkDraft.ofHeading("#".repeat(level) + " " + text, text, metadata));
    }
}
