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

package io.github.jaye.ai.askora.rag.service;

import io.github.jaye.ai.askora.rag.controller.request.AgentProfileSaveRequest;
import io.github.jaye.ai.askora.rag.controller.request.AgentPromptSaveRequest;
import io.github.jaye.ai.askora.rag.controller.vo.AgentProfileListVO;
import io.github.jaye.ai.askora.rag.controller.vo.AgentPromptConfigVO;

/**
 * 智能体档案管理服务接口
 * 提供智能体的增删改查、激活与提示词槽位配置能力
 */
public interface AgentProfileAdminService {

    /**
     * 查询全部智能体，内置在前、其余按创建时间
     */
    AgentProfileListVO list();

    /**
     * 创建智能体
     */
    String create(AgentProfileSaveRequest requestParam);

    /**
     * 更新智能体基础信息
     */
    void update(String id, AgentProfileSaveRequest requestParam);

    /**
     * 删除智能体（激活中的不允许删除）
     */
    void delete(String id);

    /**
     * 激活指定智能体，全局仅保留一条激活态
     */
    void activate(String id);

    /**
     * 查询该智能体的全部槽位配置，含元数据与当前架构下的生效判定
     */
    AgentPromptConfigVO loadPrompts(String id);

    /**
     * 保存单个槽位，内容留空即恢复回落内置智能体
     */
    void savePrompt(String id, String slotKey, AgentPromptSaveRequest requestParam);

    /**
     * 取内置智能体的该槽位内容，供控制台「从默认复制」
     */
    String defaultPrompt(String slotKey);
}
