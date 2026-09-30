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

package io.github.jaye.ai.askora.rag.core.memory;

import io.github.jaye.ai.askora.framework.convention.ChatMessage;

/**
 * 对话记忆摘要压缩服务接口
 * 当历史消息超过保留窗口后，将滑出窗口的旧消息压缩为一条摘要，替代原文进入模型上下文
 */
public interface ConversationMemorySummaryService {

    /**
     * 追加消息后判断是否需要触发摘要压缩，满足条件时异步执行
     * 只有 assistant 消息落地后才触发，保证一轮问答完整后再压缩
     */
    void compressIfNeeded(String conversationId, String userId, ChatMessage message);

    /**
     * 加载对话当前最新的一条摘要，不存在时返回 null
     */
    ChatMessage loadLatestSummary(String conversationId, String userId);

    /**
     * 将摘要包装为系统消息，便于模型识别这是压缩后的历史概览而非本轮指令
     */
    ChatMessage decorateIfNeeded(ChatMessage summary);
}
