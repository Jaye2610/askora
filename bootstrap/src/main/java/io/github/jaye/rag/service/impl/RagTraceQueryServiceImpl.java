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

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.jaye.ai.askora.rag.controller.request.RagTraceRunPageRequest;
import io.github.jaye.ai.askora.rag.controller.vo.RagTraceDetailVO;
import io.github.jaye.ai.askora.rag.controller.vo.RagTraceNodeVO;
import io.github.jaye.ai.askora.rag.controller.vo.RagTraceRunVO;
import io.github.jaye.ai.askora.rag.dao.entity.RagTraceNodeDO;
import io.github.jaye.ai.askora.rag.dao.entity.RagTraceRunDO;
import io.github.jaye.ai.askora.rag.dao.mapper.RagTraceNodeMapper;
import io.github.jaye.ai.askora.rag.dao.mapper.RagTraceRunMapper;
import io.github.jaye.ai.askora.rag.service.RagTraceQueryService;
import io.github.jaye.ai.askora.user.dao.entity.UserDO;
import io.github.jaye.ai.askora.user.dao.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * RAG Trace 查询服务实现
 * <p>
 * 面向管理后台提供一次问答全链路追踪的查询能力：分页查询 Trace Run，
 * 查看单条 Trace 及其节点明细，并补充展示用户名与首包耗时（TTFT）等指标。
 */
@Service
@RequiredArgsConstructor
public class RagTraceQueryServiceImpl implements RagTraceQueryService {

    private final RagTraceRunMapper runMapper;
    private final RagTraceNodeMapper nodeMapper;
    private final UserMapper userMapper;

    /** 按 traceId/conversationId/taskId/status 过滤分页查询 Trace Run，并附用户名与 TTFT */
    @Override
    public IPage<RagTraceRunVO> pageRuns(RagTraceRunPageRequest request) {
        LambdaQueryWrapper<RagTraceRunDO> wrapper = Wrappers.lambdaQuery(RagTraceRunDO.class)
                .orderByDesc(RagTraceRunDO::getStartTime);

        if (StrUtil.isNotBlank(request.getTraceId())) {
            wrapper.eq(RagTraceRunDO::getTraceId, request.getTraceId());
        }
        if (StrUtil.isNotBlank(request.getConversationId())) {
            wrapper.eq(RagTraceRunDO::getConversationId, request.getConversationId());
        }
        if (StrUtil.isNotBlank(request.getTaskId())) {
            wrapper.eq(RagTraceRunDO::getTaskId, request.getTaskId());
        }
        if (StrUtil.isNotBlank(request.getStatus())) {
            wrapper.eq(RagTraceRunDO::getStatus, request.getStatus());
        }

        IPage<RagTraceRunDO> pageResult = runMapper.selectPage(request, wrapper);
        Map<String, String> usernameMap = loadUsernameMap(pageResult.getRecords());
        Map<String, Long> ttftMap = loadTtftMap(pageResult.getRecords());
        return pageResult.convert(run -> toRunVO(run, usernameMap, ttftMap));
    }

    @Override
    public RagTraceDetailVO detail(String traceId) {
        RagTraceRunDO run = runMapper.selectOne(Wrappers.lambdaQuery(RagTraceRunDO.class)
                .eq(RagTraceRunDO::getTraceId, traceId)
                .last("limit 1"));
        if (run == null) {
            return null;
        }
        Map<String, String> usernameMap = loadUsernameMap(List.of(run));
        Map<String, Long> ttftMap = loadTtftMap(List.of(run));
        return RagTraceDetailVO.builder()
                .run(toRunVO(run, usernameMap, ttftMap))
                .nodes(listNodes(traceId))
                .build();
    }

    @Override
    public List<RagTraceNodeVO> listNodes(String traceId) {
        List<RagTraceNodeDO> nodes = nodeMapper.selectList(Wrappers.lambdaQuery(RagTraceNodeDO.class)
                .eq(RagTraceNodeDO::getTraceId, traceId)
                .orderByAsc(RagTraceNodeDO::getStartTime)
                .orderByAsc(RagTraceNodeDO::getId));
        return nodes.stream().map(this::toNodeVO).toList();
    }

    private RagTraceRunVO toRunVO(RagTraceRunDO run, Map<String, String> usernameMap, Map<String, Long> ttftMap) {
        String username = resolveUsername(run.getUserId(), usernameMap);
        String question = parseQuestion(run.getExtraData());
        return RagTraceRunVO.builder()
                .traceId(run.getTraceId())
                .traceName(run.getTraceName())
                .entryMethod(run.getEntryMethod())
                .conversationId(run.getConversationId())
                .taskId(run.getTaskId())
                .userId(run.getUserId())
                .username(username)
                .status(run.getStatus())
                .errorMessage(run.getErrorMessage())
                .durationMs(run.getDurationMs())
                .ttftMs(ttftMap.get(run.getTraceId()))
                .question(question)
                .startTime(run.getStartTime())
                .endTime(run.getEndTime())
                .build();
    }

    private Map<String, String> loadUsernameMap(List<RagTraceRunDO> runs) {
        if (runs == null || runs.isEmpty()) {
            return Collections.emptyMap();
        }

        Set<String> userIds = runs.stream()
                .map(RagTraceRunDO::getUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (userIds.isEmpty()) {
            return Collections.emptyMap();
        }

        List<UserDO> users = userMapper.selectList(Wrappers.lambdaQuery(UserDO.class)
                .in(UserDO::getId, userIds)
                .select(UserDO::getId, UserDO::getUsername));
        if (users == null || users.isEmpty()) {
            return Collections.emptyMap();
        }

        return users.stream().collect(Collectors.toMap(
                user -> String.valueOf(user.getId()),
                UserDO::getUsername,
                (left, right) -> left
        ));
    }

    private Map<String, Long> loadTtftMap(List<RagTraceRunDO> runs) {
        if (runs == null || runs.isEmpty()) {
            return Collections.emptyMap();
        }
        List<String> traceIds = runs.stream()
                .map(RagTraceRunDO::getTraceId)
                .filter(Objects::nonNull)
                .toList();
        if (traceIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<RagTraceNodeDO> ttftNodes = nodeMapper.selectList(
                Wrappers.lambdaQuery(RagTraceNodeDO.class)
                        .in(RagTraceNodeDO::getTraceId, traceIds)
                        .eq(RagTraceNodeDO::getNodeType, "USER_TTFT")
                        .select(RagTraceNodeDO::getTraceId, RagTraceNodeDO::getDurationMs));
        if (ttftNodes == null || ttftNodes.isEmpty()) {
            return Collections.emptyMap();
        }
        return ttftNodes.stream()
                .filter(node -> node.getTraceId() != null && node.getDurationMs() != null)
                .collect(Collectors.toMap(
                        RagTraceNodeDO::getTraceId,
                        RagTraceNodeDO::getDurationMs,
                        (left, right) -> left));
    }

    private String parseQuestion(String extraData) {
        if (StrUtil.isBlank(extraData)) {
            return null;
        }
        try {
            JSONObject json = JSONUtil.parseObj(extraData);
            return json.getStr("question");
        } catch (Exception ignored) {
            return null;
        }
    }

    private String resolveUsername(String userId, Map<String, String> usernameMap) {
        if (StrUtil.isBlank(userId) || usernameMap == null || usernameMap.isEmpty()) {
            return null;
        }
        return usernameMap.get(userId);
    }

    private RagTraceNodeVO toNodeVO(RagTraceNodeDO node) {
        return RagTraceNodeVO.builder()
                .traceId(node.getTraceId())
                .nodeId(node.getNodeId())
                .parentNodeId(node.getParentNodeId())
                .depth(node.getDepth())
                .nodeType(node.getNodeType())
                .nodeName(node.getNodeName())
                .className(node.getClassName())
                .methodName(node.getMethodName())
                .status(node.getStatus())
                .errorMessage(node.getErrorMessage())
                .durationMs(node.getDurationMs())
                .startTime(node.getStartTime())
                .endTime(node.getEndTime())
                .build();
    }
}
