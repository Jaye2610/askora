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

package io.github.jaye.ai.askora.rag.dto;

import java.util.List;

/**
 * 推荐追问生成结果
 */
public record RecommendedQuestionsPayload(Status status, List<String> questions) {

    public RecommendedQuestionsPayload {
        // 序列化即弃的传输对象，只做 null 归一，不必防御性拷贝
        questions = questions == null ? List.of() : questions;
    }

    /**
     * 构建生成成功的结果，无问题时回落为空结果
     */
    public static RecommendedQuestionsPayload success(List<String> questions) {
        return questions == null || questions.isEmpty()
                ? empty()
                : new RecommendedQuestionsPayload(Status.SUCCESS, questions);
    }

    /**
     * 构建空结果（无推荐问题）
     */
    public static RecommendedQuestionsPayload empty() {
        return new RecommendedQuestionsPayload(Status.EMPTY, List.of());
    }

    /**
     * 构建生成失败的结果
     */
    public static RecommendedQuestionsPayload failed() {
        return new RecommendedQuestionsPayload(Status.FAILED, List.of());
    }

    public enum Status {
        SUCCESS,
        EMPTY,
        FAILED
    }
}
