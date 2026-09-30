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

package io.github.jaye.ai.askora.infra.chat;

import io.github.jaye.ai.askora.framework.convention.ChatRequest;
import io.github.jaye.ai.askora.framework.trace.RagTraceNode;
import io.github.jaye.ai.askora.infra.enums.ModelProvider;
import io.github.jaye.ai.askora.infra.model.ModelTarget;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * AIHubMix 厂商聊天客户端（OpenAI 兼容协议）
 */
@Slf4j
@Service
public class AIHubMixChatClient extends AbstractOpenAIStyleChatClient {

    /**
     * 返回该客户端适配的厂商标识
     */
    /**
     * 返回该客户端适配的厂商标识
     */
    @Override
    public String provider() {
        return ModelProvider.AI_HUB_MIX.getId();
    }

    /**
     * 执行 AIHubMix 同步聊天调用
     */
    @Override
    @RagTraceNode(name = "aihubmix-chat", type = "LLM_PROVIDER")
    public String chat(ChatRequest request, ModelTarget target) {
        return doChat(request, target);
    }

    /**
     * 执行 AIHubMix 流式聊天调用
     */
    @Override
    public StreamCancellationHandle streamChat(ChatRequest request, StreamCallback callback, ModelTarget target) {
        return doStreamChat(request, callback, target);
    }
}
