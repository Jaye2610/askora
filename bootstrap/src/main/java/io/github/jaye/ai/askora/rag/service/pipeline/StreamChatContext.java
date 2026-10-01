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

package io.github.jaye.ai.askora.rag.service.pipeline;

import io.github.jaye.ai.askora.framework.convention.ChatMessage;
import io.github.jaye.ai.askora.infra.chat.StreamCallback;
import io.github.jaye.ai.askora.rag.core.rewrite.RewriteResult;
import io.github.jaye.ai.askora.rag.dto.SubQuestionIntent;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 流式对话上下文（管道内各阶段共享的载体）
 * <p>
 * 分为两类字段：
 * <ul>
 *   <li>不可变输入：进入 Pipeline 时一次性确定（问题、会话/任务标识、是否深度思考、用户、回调句柄）</li>
 *   <li>中间状态：由管道中各步骤依次回填（对话历史、问题重写结果、子问题意图）</li>
 * </ul>
 * 顶层问题在这里与后续拆解出的子问题意图并存，供检索引擎按「已重写 / 分片意图」取数。
 */
@Getter
@Builder
public class StreamChatContext {

    // ==================== 不可变输入参数 ====================

    /** 用户当前提问（原始输入） */
    private final String question;
    /** 本次请求归属的会话 ID */
    private final String conversationId;
    /** 流式任务 ID，贯穿取消/事件分发 */
    private final String taskId;
    /** 是否开启深度思考模式（影响是否输出 think 流） */
    private final boolean deepThinking;
    /** 当前用户 ID */
    private final String userId;
    /** 流式回调句柄：管道输出的所有事件最终都经它推送给前端 */
    private final StreamCallback callback;

    // ==================== 管道中填充的中间状态 ====================

    /** 会话历史（已加载并裁剪/精简约后的对话记录），供生成阶段拼 prompt 使用 */
    @Setter
    private List<ChatMessage> history;

    /** 问题重写结果：对提问的改写/澄清，供检索引擎使用 */
    @Setter
    private RewriteResult rewriteResult;

    /** 复杂问题拆解出的子问题意图列表，供多路分片检索使用 */
    @Setter
    private List<SubQuestionIntent> subIntents;
}
