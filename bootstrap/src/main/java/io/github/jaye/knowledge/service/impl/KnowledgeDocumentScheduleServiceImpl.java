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

package io.github.jaye.ai.askora.knowledge.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.jaye.ai.askora.framework.exception.ClientException;
import io.github.jaye.ai.askora.knowledge.dao.entity.KnowledgeDocumentDO;
import io.github.jaye.ai.askora.knowledge.dao.entity.KnowledgeDocumentScheduleDO;
import io.github.jaye.ai.askora.knowledge.dao.entity.KnowledgeDocumentScheduleExecDO;
import io.github.jaye.ai.askora.knowledge.dao.mapper.KnowledgeDocumentScheduleExecMapper;
import io.github.jaye.ai.askora.knowledge.dao.mapper.KnowledgeDocumentScheduleMapper;
import io.github.jaye.ai.askora.knowledge.enums.SourceType;
import io.github.jaye.ai.askora.knowledge.schedule.CronScheduleHelper;
import io.github.jaye.ai.askora.knowledge.service.KnowledgeDocumentScheduleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Date;

/**
 * 知识库文档刷新计划服务实现
 * <p>
 * 为 URL 来源的远程文档维护定时刷新计划：根据文档的 cron 表达式与启用状态
 * 计算下次执行时间，写入/更新计划表；同时支持按文档 ID 级联删除计划与执行记录。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeDocumentScheduleServiceImpl implements KnowledgeDocumentScheduleService {

    private final KnowledgeDocumentScheduleMapper scheduleMapper;
    private final KnowledgeDocumentScheduleExecMapper scheduleExecMapper;
    /** 定时周期最小间隔配置（秒），防止高频刷新压垮远程源 */
    @Value("${rag.knowledge.schedule.min-interval-seconds:60}")
    private long scheduleMinIntervalSeconds;

    /** 新增或更新文档的刷新计划（文档不存在计划时允许创建） */
    @Override
    public void upsertSchedule(KnowledgeDocumentDO documentDO) {
        syncSchedule(documentDO, true);
    }

    /** 仅当文档已存在刷新计划时同步其 cron 与启用状态（不新建） */
    @Override
    public void syncScheduleIfExists(KnowledgeDocumentDO documentDO) {
        syncSchedule(documentDO, false);
    }

    /**
     * 同步文档刷新计划：仅处理 URL 来源且启用中的文档；校验 cron 周期下限后
     * 计算下次执行时间，最后按是否存在计划执行插入或更新
     */
    private void syncSchedule(KnowledgeDocumentDO documentDO, boolean allowCreate) {
        if (documentDO == null) {
            return;
        }
        if (documentDO.getId() == null || documentDO.getKbId() == null) {
            return;
        }
        if (!SourceType.URL.getValue().equalsIgnoreCase(documentDO.getSourceType())) {
            return;
        }
        boolean docEnabled = documentDO.getEnabled() == null || documentDO.getEnabled() == 1;
        String cron = documentDO.getScheduleCron();
        boolean enabled = documentDO.getScheduleEnabled() != null && documentDO.getScheduleEnabled() == 1;
        if (!StringUtils.hasText(cron)) {
            enabled = false;
        }
        if (!docEnabled) {
            enabled = false;
        }

        Date nextRunTime = null;
        if (enabled) {
            try {
                if (CronScheduleHelper.isIntervalLessThan(cron, new Date(), scheduleMinIntervalSeconds)) {
                    throw new ClientException("定时周期不能小于 " + scheduleMinIntervalSeconds + " 秒");
                }
                nextRunTime = CronScheduleHelper.nextRunTime(cron, new Date());
            } catch (IllegalArgumentException e) {
                throw new ClientException("定时表达式不合法");
            }
        }

        KnowledgeDocumentScheduleDO existing = scheduleMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeDocumentScheduleDO>()
                        .eq(KnowledgeDocumentScheduleDO::getDocId, documentDO.getId())
                        .last("LIMIT 1")
        );

        if (existing == null) {
            if (!allowCreate) {
                return;
            }
            KnowledgeDocumentScheduleDO schedule = KnowledgeDocumentScheduleDO.builder()
                    .docId(documentDO.getId())
                    .kbId(documentDO.getKbId())
                    .cronExpr(cron)
                    .enabled(enabled ? 1 : 0)
                    .nextRunTime(nextRunTime)
                    .build();
            scheduleMapper.insert(schedule);
        } else {
            scheduleMapper.update(
                    new LambdaUpdateWrapper<KnowledgeDocumentScheduleDO>()
                            .eq(KnowledgeDocumentScheduleDO::getId, existing.getId())
                            .set(KnowledgeDocumentScheduleDO::getCronExpr, cron)
                            .set(KnowledgeDocumentScheduleDO::getEnabled, enabled ? 1 : 0)
                            .set(KnowledgeDocumentScheduleDO::getNextRunTime, nextRunTime)
            );
        }
    }

    /** 级联删除某文档的刷新计划及其历史执行记录 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteByDocId(String docId) {
        if (!StringUtils.hasText(docId)) {
            return;
        }
        scheduleExecMapper.delete(new LambdaQueryWrapper<KnowledgeDocumentScheduleExecDO>()
                .eq(KnowledgeDocumentScheduleExecDO::getDocId, docId));
        scheduleMapper.delete(new LambdaQueryWrapper<KnowledgeDocumentScheduleDO>()
                .eq(KnowledgeDocumentScheduleDO::getDocId, docId));
    }
}
