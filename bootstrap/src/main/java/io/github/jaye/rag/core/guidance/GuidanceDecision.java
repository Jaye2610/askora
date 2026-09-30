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

package io.github.jaye.ai.askora.rag.core.guidance;

import lombok.Getter;

/**
 * 引导式问答决策结果类
 * 用于表示是否需要向用户输出引导式问答提示
 */
@Getter
public class GuidanceDecision {

    /**
     * 决策动作：NONE 无需引导、PROMPT 需输出澄清提示
     */
    public enum Action {
        NONE,
        PROMPT
    }

    private final Action action;
    private final String prompt;

    private GuidanceDecision(Action action, String prompt) {
        this.action = action;
        this.prompt = prompt;
    }

    /**
     * 无需引导的决策
     */
    public static GuidanceDecision none() {
        return new GuidanceDecision(Action.NONE, null);
    }

    /**
     * 需输出澄清提示的决策
     */
    public static GuidanceDecision prompt(String prompt) {
        return new GuidanceDecision(Action.PROMPT, prompt);
    }

    /**
     * 是否需要向用户输出澄清提示
     */
    public boolean isPrompt() {
        return action == Action.PROMPT;
    }
}
