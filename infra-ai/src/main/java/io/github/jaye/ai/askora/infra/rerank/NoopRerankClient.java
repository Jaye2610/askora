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

package io.github.jaye.ai.askora.infra.rerank;

import io.github.jaye.ai.askora.framework.convention.RetrievedChunk;
import io.github.jaye.ai.askora.infra.enums.ModelProvider;
import io.github.jaye.ai.askora.infra.model.ModelTarget;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 空实现重排客户端
 * 不调用任何模型，直接按原顺序截取前 topN 条，作为未配置重排模型时的兜底
 */
@Service
public class NoopRerankClient implements RerankClient {

    /**
     * 返回 NOOP 提供商标识
     */
    @Override
    public String provider() {
        return ModelProvider.NOOP.getId();
    }

    /**
     * 不做任何重排，直接按原顺序截取前 topN 条
     */
    @Override
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topN, ModelTarget target) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        if (topN <= 0 || candidates.size() <= topN) {
            return candidates;
        }
        return candidates.stream()
                .limit(topN)
                .collect(Collectors.toList());
    }
}
