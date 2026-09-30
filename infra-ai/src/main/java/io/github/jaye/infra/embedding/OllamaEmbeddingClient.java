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

package io.github.jaye.ai.askora.infra.embedding;

import com.google.gson.JsonObject;
import io.github.jaye.ai.askora.infra.enums.ModelProvider;
import io.github.jaye.ai.askora.infra.model.ModelTarget;
import okhttp3.OkHttpClient;
import org.springframework.stereotype.Service;

/**
 * Ollama 本地向量化客户端
 * 复用 OpenAI 兼容协议；本地部署无需 API Key，端点也不支持 encoding_format 字段
 */
@Service
public class OllamaEmbeddingClient extends AbstractOpenAIStyleEmbeddingClient {

    public OllamaEmbeddingClient(OkHttpClient syncHttpClient) {
        super(syncHttpClient);
    }

    /**
     * 返回 Ollama 提供商标识
     */
    @Override
    public String provider() {
        return ModelProvider.OLLAMA.getId();
    }

    /**
     * Ollama 为本地部署，不要求 API Key
     */
    @Override
    protected boolean requiresApiKey() {
        return false;
    }

    /**
     * 覆写为空操作：Ollama 端点不接受 encoding_format 字段
     */
    @Override
    protected void customizeRequestBody(JsonObject body, ModelTarget target) {
        // Ollama 不需要 encoding_format 字段
    }
}
