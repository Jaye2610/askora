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

import io.github.jaye.ai.askora.rag.config.RAGDefaultProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * PgVector 向量库管理器，负责共享表 HNSW 索引的创建、存在性检查与按集合清理
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rag.vector.type", havingValue = "pg")
public class PgVectorStoreAdmin implements VectorStoreAdmin {


    private final JdbcTemplate jdbcTemplate;
    private final RAGDefaultProperties ragDefaultProperties;

    /**
     * 幂等确保共享表上的 HNSW 余弦索引存在，已存在则直接跳过
     */
    @Override
    public void ensureVectorSpace(VectorSpaceSpec spec) {
        String indexName = "idx_kv_embedding_hnsw";

        // noinspection SqlDialectInspection,SqlNoDataSourceInspection
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM pg_indexes WHERE indexname = ?", Integer.class, indexName);

        if (count != null && count > 0) {
            log.debug("HNSW索引已存在: {}", indexName);
            return;
        }

        int dimension = ragDefaultProperties.getDimension();
        log.info("创建pgvector HNSW索引，维度: {}", dimension);
        jdbcTemplate.execute(String.format("CREATE INDEX IF NOT EXISTS %s ON t_knowledge_vector USING hnsw (embedding vector_cosine_ops)", indexName));
    }

    /**
     * 通过探测共享表是否可访问来判断向量空间存在性
     */
    @Override
    public boolean vectorSpaceExists(VectorSpaceId spaceId) {
        try {
            // noinspection SqlDialectInspection,SqlNoDataSourceInspection
            jdbcTemplate.queryForObject("SELECT COUNT(*) FROM t_knowledge_vector LIMIT 1", Integer.class);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 删除共享表中该 collection 的残留向量行（不动共享 HNSW 索引）
     */
    @Override
    public void dropVectorSpace(String collectionName) {
        // PG 为共享表：仅删除该 collection 的残留向量行，不动共享 HNSW 索引
        // 常规情况下文档删除已逐一清理，此处多为 0 行的兜底
        // noinspection SqlDialectInspection,SqlNoDataSourceInspection
        int deleted = jdbcTemplate.update("DELETE FROM t_knowledge_vector WHERE collection_name = ?", collectionName);
        log.info("已删除 collection={} 的残留向量行，count={}", collectionName, deleted);
    }
}
