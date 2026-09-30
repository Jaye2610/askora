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

package io.github.jaye.ai.askora.core.ingest;

import io.github.jaye.ai.askora.core.chunk.model.ChunkBudget;
import io.github.jaye.ai.askora.core.parser.registry.ParseProfile;

/**
 * 文档级摄取配置（L3）：这一篇怎么解析、怎么切，对应 {@code t_knowledge_document.ingestion_spec} 一个 JSONB 列
 * <p>
 * 不含 embeddingModel：嵌入模型是知识库级（L2）约束性配置，文档级无权覆盖，只能由 {@link VectorTarget} 提供
 *
 * @param version      结构版本，用于未来演进时识别旧值
 * @param parseProfile 解析档位
 * @param budget       分块预算
 */
public record IngestionSpec(int version, ParseProfile parseProfile, ChunkBudget budget) {

    /**
     * 当前结构版本
     */
    public static final int CURRENT_VERSION = 2;

    public IngestionSpec {
        if (version <= 0) {
            throw new IllegalArgumentException("version 必须 > 0，实际 " + version);
        }
        parseProfile = parseProfile == null ? ParseProfile.defaultProfile() : parseProfile;
        budget = budget == null ? ChunkBudget.defaults() : budget;
    }

    /**
     * 默认配置：默认解析档位 + 默认分块预算
     */
    public static IngestionSpec defaults() {
        return new IngestionSpec(CURRENT_VERSION, ParseProfile.defaultProfile(), ChunkBudget.defaults());
    }

    /**
     * 以当前版本组装一份显式配置
     */
    public static IngestionSpec of(ParseProfile parseProfile, ChunkBudget budget) {
        return new IngestionSpec(CURRENT_VERSION, parseProfile, budget);
    }
}
