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

package io.github.jaye.ai.askora.audit.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import io.github.jaye.ai.askora.audit.controller.request.BizChangeLogPageRequest;
import io.github.jaye.ai.askora.audit.controller.vo.BizChangeLogVO;

/**
 * 业务变更审计日志服务
 * <p>
 * 提供管理后台对配置变更审计日志的分页查询与详情查看能力。
 */
public interface BizChangeLogService {

    /** 按业务类型、业务 ID、操作类型、操作人等条件分页查询变更审计日志 */
    IPage<BizChangeLogVO> page(BizChangeLogPageRequest requestParam);

    /** 根据日志 ID 查询单条变更审计日志详情 */
    BizChangeLogVO get(String id);
}
