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

package io.github.jaye.ai.askora.rag.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.jaye.ai.askora.rag.dao.entity.ConversationDO;
import io.github.jaye.ai.askora.rag.dao.entity.ConversationMessageDO;
import io.github.jaye.ai.askora.rag.dao.entity.ConversationSummaryDO;
import io.github.jaye.ai.askora.rag.dao.mapper.ConversationMapper;
import io.github.jaye.ai.askora.rag.dao.mapper.ConversationMessageMapper;
import io.github.jaye.ai.askora.rag.dao.mapper.ConversationSummaryMapper;
import io.github.jaye.ai.askora.rag.service.ConversationGroupService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 对话组服务实现类
 * 面向对话记忆加载场景，提供消息、摘要与会话信息的查询能力
 */
@Service
@RequiredArgsConstructor
public class ConversationGroupServiceImpl implements ConversationGroupService {

    private final ConversationMessageMapper messageMapper;
    private final ConversationSummaryMapper summaryMapper;
    private final ConversationMapper conversationMapper;

    /**
     * 查询对话中最新 N 条用户消息（按时间倒序），用于记忆压缩时截取近期提问
     */
    @Override
    public List<ConversationMessageDO> listLatestUserOnlyMessages(String conversationId, String userId, int limit) {
        if (StrUtil.isBlank(conversationId) || StrUtil.isBlank(userId) || limit <= 0) {
            return List.of();
        }
        return messageMapper.selectList(
                Wrappers.lambdaQuery(ConversationMessageDO.class)
                        .eq(ConversationMessageDO::getConversationId, conversationId)
                        .eq(ConversationMessageDO::getUserId, userId)
                        .eq(ConversationMessageDO::getRole, "user")
                        .eq(ConversationMessageDO::getDeleted, 0)
                        .orderByDesc(ConversationMessageDO::getCreateTime)
                        .last("limit " + limit)
        );
    }

    /**
     * 查询 (afterId, beforeId) 开区间内的 user/assistant 消息，按 ID 升序
     */
    @Override
    public List<ConversationMessageDO> listMessagesBetweenIds(String conversationId, String userId, String afterId, String beforeId) {
        if (StrUtil.isBlank(conversationId) || StrUtil.isBlank(userId)) {
            return List.of();
        }
        var query = Wrappers.lambdaQuery(ConversationMessageDO.class)
                .eq(ConversationMessageDO::getConversationId, conversationId)
                .eq(ConversationMessageDO::getUserId, userId)
                .in(ConversationMessageDO::getRole, "user", "assistant")
                .eq(ConversationMessageDO::getDeleted, 0);
        if (afterId != null) {
            query.gt(ConversationMessageDO::getId, afterId);
        }
        if (beforeId != null) {
            query.lt(ConversationMessageDO::getId, beforeId);
        }
        return messageMapper.selectList(
                query.orderByAsc(ConversationMessageDO::getId)
        );
    }

    /**
     * 查找指定时间点之前（含）的最大消息 ID，用于摘要节点定位
     */
    @Override
    public String findMaxMessageIdAtOrBefore(String conversationId, String userId, java.util.Date at) {
        if (StrUtil.isBlank(conversationId) || StrUtil.isBlank(userId) || at == null) {
            return null;
        }
        ConversationMessageDO record = messageMapper.selectOne(
                Wrappers.lambdaQuery(ConversationMessageDO.class)
                        .eq(ConversationMessageDO::getConversationId, conversationId)
                        .eq(ConversationMessageDO::getUserId, userId)
                        .eq(ConversationMessageDO::getDeleted, 0)
                        .le(ConversationMessageDO::getCreateTime, at)
                        .orderByDesc(ConversationMessageDO::getId)  //雪花函数的id是按时间递增的
                        .last("limit 1")
        );
        return record == null ? null : record.getId();
    }

    /**
     * 统计用户在指定对话中的用户消息数，用于判断是否触发摘要压缩
     */
    @Override
    public long countUserMessages(String conversationId, String userId) {
        if (StrUtil.isBlank(conversationId) || StrUtil.isBlank(userId)) {
            return 0;
        }
        return messageMapper.selectCount(
                Wrappers.lambdaQuery(ConversationMessageDO.class)
                        .eq(ConversationMessageDO::getConversationId, conversationId)
                        .eq(ConversationMessageDO::getUserId, userId)
                        .eq(ConversationMessageDO::getRole, "user")
                        .eq(ConversationMessageDO::getDeleted, 0)
        );
    }

    /**
     * 查询对话最新一条摘要，不存在返回 null
     */
    @Override
    public ConversationSummaryDO findLatestSummary(String conversationId, String userId) {
        if (StrUtil.isBlank(conversationId) || StrUtil.isBlank(userId)) {
            return null;
        }
        return summaryMapper.selectOne(
                Wrappers.lambdaQuery(ConversationSummaryDO.class)
                        .eq(ConversationSummaryDO::getConversationId, conversationId)
                        .eq(ConversationSummaryDO::getUserId, userId)
                        .eq(ConversationSummaryDO::getDeleted, 0)
                        .orderByDesc(ConversationSummaryDO::getId)
                        .last("limit 1")
        );
    }

    /**
     * 查询用户归属的会话记录，不存在返回 null
     */
    @Override
    public ConversationDO findConversation(String conversationId, String userId) {
        if (StrUtil.isBlank(conversationId) || StrUtil.isBlank(userId)) {
            return null;
        }
        return conversationMapper.selectOne(
                Wrappers.lambdaQuery(ConversationDO.class)
                        .eq(ConversationDO::getConversationId, conversationId)
                        .eq(ConversationDO::getUserId, userId)
                        .eq(ConversationDO::getDeleted, 0)
        );
    }
}
