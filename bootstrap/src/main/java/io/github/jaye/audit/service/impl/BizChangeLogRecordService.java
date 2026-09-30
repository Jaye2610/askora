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

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mzt.logapi.beans.CodeVariableType;
import com.mzt.logapi.beans.LogRecord;
import com.mzt.logapi.service.ILogRecordService;
import io.github.jaye.ai.askora.audit.dao.entity.BizChangeLogDO;
import io.github.jaye.ai.askora.audit.dao.mapper.BizChangeLogMapper;
import io.github.jaye.ai.askora.framework.context.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Map;

/**
 * 业务变更审计日志落库/查询适配器
 * <p>
 * 实现 mzt-logapi 的 {@link ILogRecordService}，把日志框架产生的 {@link LogRecord}
 * 转换为 {@link BizChangeLogDO} 持久化到 t_biz_change_log；同时支持按业务单号 +
 * 类型（可带操作子类型）回查日志。落库使用 REQUIRES_NEW 独立事务，
 * 即使主业务失败也不影响审计记录写入。
 */
@Service
@RequiredArgsConstructor
public class BizChangeLogRecordService implements ILogRecordService {

    private static final String UNKNOWN_BIZ_ID = "UNKNOWN";

    private final BizChangeLogMapper bizChangeLogMapper;
    private final ObjectMapper objectMapper;

    /**
     * 持久化一条审计日志：从日志框架对象与当前请求上下文抽取业务、快照、操作人等信息
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(LogRecord logRecord) {
        JsonNode extra = parseExtra(logRecord.getExtra());
        HttpServletRequest request = currentRequest();
        BizChangeLogDO record = BizChangeLogDO.builder()
                .bizType(limit(logRecord.getType(), 64))
                .bizId(limit(StringUtils.hasText(logRecord.getBizNo()) ? logRecord.getBizNo() : UNKNOWN_BIZ_ID, 64))
                .operationType(limit(logRecord.getSubType(), 32))
                .actionDesc(limit(logRecord.getAction(), 512))
                .beforeSnapshot(jsonNodeToString(extra.get("beforeSnapshot")))
                .afterSnapshot(jsonNodeToString(extra.get("afterSnapshot")))
                .changeDiff(jsonNodeToString(extra.get("changeDiff")))
                .operatorId(limit(resolveOperatorId(logRecord), 64))
                .operatorName(limit(UserContext.getUsername(), 128))
                .operatorRole(limit(UserContext.getRole(), 64))
                .success(!logRecord.isFail())
                .errorMessage(logRecord.isFail() ? logRecord.getAction() : null)
                .className(limit(resolveClassName(logRecord.getCodeVariable()), 255))
                .methodName(limit(resolveMethodName(logRecord.getCodeVariable()), 255))
                .ip(limit(resolveIp(request), 64))
                .userAgent(limit(request == null ? null : request.getHeader("User-Agent"), 512))
                .createTime(logRecord.getCreateTime())
                .build();
        bizChangeLogMapper.insert(record);
    }

    /** 按业务单号 + 业务类型倒序查询该业务的最新 100 条审计日志 */
    @Override
    public List<LogRecord> queryLog(String bizNo, String type) {
        return bizChangeLogMapper.selectList(Wrappers.lambdaQuery(BizChangeLogDO.class)
                        .eq(BizChangeLogDO::getBizId, bizNo)
                        .eq(BizChangeLogDO::getBizType, type)
                        .orderByDesc(BizChangeLogDO::getCreateTime)
                        .last("LIMIT 100"))
                .stream()
                .map(this::toLogRecord)
                .toList();
    }

    /** 按业务单号 + 业务类型 + 操作子类型倒序查询该业务的最新 100 条审计日志 */
    @Override
    public List<LogRecord> queryLogByBizNo(String bizNo, String type, String subType) {
        return bizChangeLogMapper.selectList(Wrappers.lambdaQuery(BizChangeLogDO.class)
                        .eq(BizChangeLogDO::getBizId, bizNo)
                        .eq(BizChangeLogDO::getBizType, type)
                        .eq(BizChangeLogDO::getOperationType, subType)
                        .orderByDesc(BizChangeLogDO::getCreateTime)
                        .last("LIMIT 100"))
                .stream()
                .map(this::toLogRecord)
                .toList();
    }

    /** 解析日志框架附带的额外 JSON（含 before/after 快照与 diff） */
    private JsonNode parseExtra(String extra) {
        if (!StringUtils.hasText(extra)) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(extra);
        } catch (JsonProcessingException e) {
            return objectMapper.createObjectNode();
        }
    }

    private String jsonNodeToString(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        return node.toString();
    }

    /** 解析操作人 ID：优先取当前登录用户，其次取日志携带的操作人，兜底 SYSTEM */
    private String resolveOperatorId(LogRecord logRecord) {
        String userId = UserContext.getUserId();
        if (StringUtils.hasText(userId)) {
            return userId;
        }
        return StringUtils.hasText(logRecord.getOperator()) ? logRecord.getOperator() : "SYSTEM";
    }

    private String resolveClassName(Map<CodeVariableType, Object> codeVariable) {
        if (codeVariable == null || !codeVariable.containsKey(CodeVariableType.ClassName)) {
            return null;
        }
        Object value = codeVariable.get(CodeVariableType.ClassName);
        if (value instanceof Class<?> clazz) {
            return clazz.getName();
        }
        return String.valueOf(value);
    }

    private String resolveMethodName(Map<CodeVariableType, Object> codeVariable) {
        if (codeVariable == null || !codeVariable.containsKey(CodeVariableType.MethodName)) {
            return null;
        }
        Object value = codeVariable.get(CodeVariableType.MethodName);
        return value == null ? null : String.valueOf(value);
    }

    private HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest();
        }
        return null;
    }

    /**
     * 解析客户端真实 IP：优先取 X-Forwarded-For 首段，其次 X-Real-IP，最后取远端地址
     */
    private String resolveIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String forwardedFor = firstHeaderValue(request.getHeader("X-Forwarded-For"));
        if (StringUtils.hasText(forwardedFor)) {
            return forwardedFor;
        }
        String realIp = request.getHeader("X-Real-IP");
        return StringUtils.hasText(realIp) ? realIp : request.getRemoteAddr();
    }

    private String firstHeaderValue(String headerValue) {
        if (!StringUtils.hasText(headerValue)) {
            return null;
        }
        int commaIndex = headerValue.indexOf(',');
        return commaIndex >= 0 ? headerValue.substring(0, commaIndex).trim() : headerValue.trim();
    }

    private String limit(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    /** 把持久化记录转回日志框架对象，供二次消费 */
    private LogRecord toLogRecord(BizChangeLogDO record) {
        return LogRecord.builder()
                .id(record.getId())
                .type(record.getBizType())
                .bizNo(record.getBizId())
                .subType(record.getOperationType())
                .operator(record.getOperatorId())
                .action(record.getActionDesc())
                .fail(!Boolean.TRUE.equals(record.getSuccess()))
                .createTime(record.getCreateTime())
                .build();
    }
}
