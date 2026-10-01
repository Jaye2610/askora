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

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import io.github.jaye.ai.askora.framework.context.UserContext;
import io.github.jaye.ai.askora.infra.chat.StreamCallback;
import io.github.jaye.ai.askora.rag.service.ratelimit.ChatQueueLimiter;
import io.github.jaye.ai.askora.rag.service.RAGChatService;
import io.github.jaye.ai.askora.rag.service.handler.StreamCallbackFactory;
import io.github.jaye.ai.askora.rag.service.handler.StreamTaskManager;
import io.github.jaye.ai.askora.rag.service.pipeline.StreamChatContext;
import io.github.jaye.ai.askora.rag.service.pipeline.StreamChatPipeline;
import io.github.jaye.ai.askora.rag.trace.StreamChatTraceRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * RAG 对话服务默认实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RAGChatServiceImpl implements RAGChatService {

    private final StreamChatPipeline chatPipeline;
    private final ChatQueueLimiter chatQueueLimiter;
    private final StreamCallbackFactory callbackFactory;
    private final StreamChatTraceRunner traceRunner;
    private final StreamTaskManager taskManager;

    /**
     * SSE 流式对话入口
     * <p>
     * 会话 ID 为空时生成雪花 ID 开启新会话；先创建 SSE 回调，再经排队限流后执行：
     * 链路追踪包裹回调（埋点），内部构建上下文并交给流水线完成流式回答
     */
    @Override
    public void streamChat(String question, String conversationId, Boolean deepThinking, SseEmitter emitter) {
        String actualConversationId = StrUtil.isBlank(conversationId) ? IdUtil.getSnowflakeNextIdStr() : conversationId;
        String taskId = IdUtil.getSnowflakeNextIdStr();
        StreamCallback callback = callbackFactory.createChatEventHandler(emitter, actualConversationId, taskId);

        chatQueueLimiter.enqueue(question, actualConversationId, emitter,
                () -> traceRunner.run(question, actualConversationId, taskId, callback, traceAware -> {
                    StreamChatContext ctx = StreamChatContext.builder()
                            .question(question)
                            .conversationId(actualConversationId)
                            .taskId(taskId)
                            .deepThinking(Boolean.TRUE.equals(deepThinking))
                            .userId(UserContext.getUserId())
                            .callback(traceAware)
                            .build();
                    chatPipeline.execute(ctx);
                }));
    }

    /**
     * 停止正在流式输出的任务
     */
    @Override
    public void stopTask(String taskId) {
        taskManager.cancel(taskId);
    }
}
