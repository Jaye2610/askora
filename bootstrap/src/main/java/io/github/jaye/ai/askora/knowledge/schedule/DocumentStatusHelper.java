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

package io.github.jaye.ai.askora.knowledge.schedule;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.jaye.ai.askora.framework.exception.ClientException;
import io.github.jaye.ai.askora.knowledge.dao.entity.KnowledgeDocumentDO;
import io.github.jaye.ai.askora.knowledge.dao.mapper.KnowledgeDocumentMapper;
import io.github.jaye.ai.askora.knowledge.enums.DocumentStatus;
import io.github.jaye.ai.askora.rag.dto.StoredFileDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;

/**
 * 文档状态流转辅助组件
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentStatusHelper {

    private static final String SYSTEM_USER = "system";

    private final KnowledgeDocumentMapper documentMapper;

    /**
     * 尝试将文档置为向量化中状态
     * <p>
     * 带状态条件的 CAS 更新：仅当文档处于非 running、启用且未删除时才成功，防止并发重复处理同一文档
     */
    public boolean tryMarkRunning(String docId) {
        // Wrapper 更新不触发 updateTime 自动填充, 显式刷新, 使卡死恢复以分块开始时刻为基准
        return documentMapper.update(
                Wrappers.lambdaUpdate(KnowledgeDocumentDO.class)
                        .set(KnowledgeDocumentDO::getStatus, DocumentStatus.RUNNING.getCode())
                        .set(KnowledgeDocumentDO::getUpdatedBy, SYSTEM_USER)
                        .set(KnowledgeDocumentDO::getUpdateTime, new Date())
                        .eq(KnowledgeDocumentDO::getId, docId)
                        .eq(KnowledgeDocumentDO::getDeleted, 0)
                        .eq(KnowledgeDocumentDO::getEnabled, 1)
                        .ne(KnowledgeDocumentDO::getStatus, DocumentStatus.RUNNING.getCode())
        ) > 0;
    }

    /**
     * 文档处于向量化中时将其置为失败状态
     */
    public void markFailedIfRunning(String docId) {
        documentMapper.update(
                Wrappers.lambdaUpdate(KnowledgeDocumentDO.class)
                        .set(KnowledgeDocumentDO::getStatus, DocumentStatus.FAILED.getCode())
                        .set(KnowledgeDocumentDO::getUpdatedBy, SYSTEM_USER)
                        .eq(KnowledgeDocumentDO::getId, docId)
                        .eq(KnowledgeDocumentDO::getStatus, DocumentStatus.RUNNING.getCode())
        );
    }

    /**
     * 定时刷新拉取到新文件后，将对象存储元信息（文件名、地址、类型、大小）回写到文档
     */
    public void applyRefreshedFileMetadata(String docId, StoredFileDTO stored) {
        KnowledgeDocumentDO update = KnowledgeDocumentDO.builder()
                .id(docId)
                .docName(stored.getOriginalFilename())
                .fileUrl(stored.getUrl())
                .fileType(stored.getDetectedType())
                .fileSize(stored.getSize())
                .updatedBy(SYSTEM_USER)
                .build();
        int updated = documentMapper.updateById(update);
        if (updated == 0) {
            throw new ClientException("文档不存在");
        }
    }

    /**
     * 恢复卡死在向量化中的文档
     * <p>
     * 超过超时时间（下限 10 分钟）仍处于 running 的文档视为进程异常中断，
     * 统一重置为 failed，避免状态永久卡死阻塞后续刷新
     */
    public StuckRecoveryResult recoverStuckRunning(long timeoutMinutes) {
        long safeTimeout = Math.max(timeoutMinutes, 10);
        Date threshold = new Date(System.currentTimeMillis() - safeTimeout * 60 * 1000);

        List<String> stuckDocIds = documentMapper.selectList(
                Wrappers.lambdaQuery(KnowledgeDocumentDO.class)
                        .select(KnowledgeDocumentDO::getId)
                        .eq(KnowledgeDocumentDO::getStatus, DocumentStatus.RUNNING.getCode())
                        .eq(KnowledgeDocumentDO::getEnabled, 1)
                        .lt(KnowledgeDocumentDO::getUpdateTime, threshold)
        ).stream().map(KnowledgeDocumentDO::getId).toList();

        if (stuckDocIds.isEmpty()) {
            return new StuckRecoveryResult(List.of(), 0);
        }

        int updated = documentMapper.update(
                Wrappers.lambdaUpdate(KnowledgeDocumentDO.class)
                        .set(KnowledgeDocumentDO::getStatus, DocumentStatus.FAILED.getCode())
                        .set(KnowledgeDocumentDO::getUpdatedBy, SYSTEM_USER)
                        .in(KnowledgeDocumentDO::getId, stuckDocIds)
                        .eq(KnowledgeDocumentDO::getStatus, DocumentStatus.RUNNING.getCode())
        );

        if (updated != stuckDocIds.size()) {
            log.warn("卡死文档恢复时部分候选状态已变化: 候选 {} 个, 实际重置 {} 个",
                    stuckDocIds.size(), updated);
        }

        return new StuckRecoveryResult(stuckDocIds, updated);
    }

    /**
     * 卡死文档恢复结果：候选文档 ID 列表与实际重置数量
     */
    public record StuckRecoveryResult(List<String> stuckDocIds, int actualRecovered) {
    }
}
