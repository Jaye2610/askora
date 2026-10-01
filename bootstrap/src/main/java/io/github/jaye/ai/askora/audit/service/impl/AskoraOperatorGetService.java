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

package io.github.jaye.ai.askora.audit.service.impl;

import com.mzt.logapi.beans.Operator;
import com.mzt.logapi.service.IOperatorGetService;
import io.github.jaye.ai.askora.framework.context.UserContext;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 审计日志操作人解析
 * <p>
 * 实现 mzt-logapi 的 {@link IOperatorGetService}，把当前登录用户上下文转换为
 * 日志框架需要的 {@link Operator}；未登录时用用户名，仍为空则回退为 SYSTEM，
 * 保证审计日志总能记录到操作来源。
 */
@Component
public class AskoraOperatorGetService implements IOperatorGetService {

    private static final String SYSTEM_OPERATOR = "SYSTEM";

    /** 解析当前审计操作人：优先用户 ID，其次用户名，兜底 SYSTEM */
    @Override
    public Operator getUser() {
        String userId = UserContext.getUserId();
        if (StringUtils.hasText(userId)) {
            return new Operator(userId);
        }
        String username = UserContext.getUsername();
        return new Operator(StringUtils.hasText(username) ? username : SYSTEM_OPERATOR);
    }
}
