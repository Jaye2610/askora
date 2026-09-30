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

package io.github.jaye.ai.askora.ingestion.service.impl;

import cn.hutool.core.lang.Assert;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mzt.logapi.starter.annotation.LogRecord;
import io.github.jaye.ai.askora.audit.constant.BizChangeBizType;
import io.github.jaye.ai.askora.audit.constant.BizChangeOperationType;
import io.github.jaye.ai.askora.audit.support.BizChangeLogContext;
import io.github.jaye.ai.askora.rag.controller.request.DocumentSourceRequest;
import io.github.jaye.ai.askora.ingestion.controller.request.IngestionTaskCreateRequest;
import io.github.jaye.ai.askora.ingestion.controller.vo.IngestionTaskNodeVO;
import io.github.jaye.ai.askora.ingestion.controller.vo.IngestionTaskVO;
import io.github.jaye.ai.askora.ingestion.dao.entity.IngestionTaskDO;
import io.github.jaye.ai.askora.ingestion.dao.entity.IngestionTaskNodeDO;
import io.github.jaye.ai.askora.ingestion.dao.mapper.IngestionTaskMapper;
import io.github.jaye.ai.askora.ingestion.dao.mapper.IngestionTaskNodeMapper;
import io.github.jaye.ai.askora.framework.context.UserContext;
import io.github.jaye.ai.askora.framework.exception.ClientException;
import io.github.jaye.ai.askora.ingestion.domain.context.DocumentSource;
import io.github.jaye.ai.askora.ingestion.domain.context.IngestionContext;
import io.github.jaye.ai.askora.ingestion.domain.context.NodeLog;
import io.github.jaye.ai.askora.ingestion.domain.enums.IngestionNodeType;
import io.github.jaye.ai.askora.ingestion.domain.enums.IngestionStatus;
import io.github.jaye.ai.askora.ingestion.domain.enums.SourceType;
import io.github.jaye.ai.askora.ingestion.domain.pipeline.NodeConfig;
import io.github.jaye.ai.askora.ingestion.domain.pipeline.PipelineDefinition;
import io.github.jaye.ai.askora.ingestion.domain.result.IngestionResult;
import io.github.jaye.ai.askora.ingestion.engine.IngestionEngine;
import io.github.jaye.ai.askora.core.parser.mime.MimeTypeDetector;
import io.github.jaye.ai.askora.rag.core.vector.VectorSpaceId;
import io.github.jaye.ai.askora.ingestion.service.IngestionPipelineService;
import io.github.jaye.ai.askora.ingestion.service.IngestionTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 数据摄入任务服务实现
 */
@Service
@RequiredArgsConstructor
public class IngestionTaskServiceImpl implements IngestionTaskService {

    private final IngestionEngine engine;
    private final IngestionPipelineService pipelineService;
    private final IngestionTaskMapper taskMapper;
    private final IngestionTaskNodeMapper taskNodeMapper;
    private final ObjectMapper objectMapper;
    private final BizChangeLogContext bizChangeLogContext;

    /**
     * 执行数据摄入任务
     *
     * <p>根据请求中的文档来源构建 DocumentSource，交由流水线引擎执行 ETL，
     * 并将任务快照写入变更审计日志。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    @LogRecord(
            success = "执行采集任务：{{#_ret.taskId}}",
            fail = "执行采集任务失败：{{#_errorMsg}}",
            type = BizChangeBizType.INGESTION_TASK,
            subType = BizChangeOperationType.RUN,
            bizNo = BizChangeLogContext.BIZ_ID_EXPRESSION,
            extra = BizChangeLogContext.SNAPSHOT_EXPRESSION,
            condition = BizChangeLogContext.RECORD_CONDITION
    )
    public IngestionResult execute(IngestionTaskCreateRequest request) {
        Assert.notNull(request, () -> new ClientException("请求不能为空"));
        DocumentSource source = toSource(request.getSource());
        IngestionResult result = executeInternal(request.getPipelineId(), source, null, null, request.getVectorSpaceId());
        putTaskSnapshot(result);
        return result;
    }

    /**
     * 上传文件并执行摄入任务
     *
     * <p>读取上传文件字节并探测 MIME 类型，以 FILE 类型文档来源进入流水线；
     * 读取文件失败时抛出客户端异常。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    @LogRecord(
            success = "上传并执行采集任务：{{#_ret.taskId}}",
            fail = "上传并执行采集任务失败：{{#_errorMsg}}",
            type = BizChangeBizType.INGESTION_TASK,
            subType = BizChangeOperationType.RUN,
            bizNo = BizChangeLogContext.BIZ_ID_EXPRESSION,
            extra = BizChangeLogContext.SNAPSHOT_EXPRESSION,
            condition = BizChangeLogContext.RECORD_CONDITION
    )
    public IngestionResult upload(String pipelineId, MultipartFile file) {
        Assert.notNull(file, () -> new ClientException("文件不能为空"));
        try {
            byte[] bytes = file.getBytes();
            String fileName = file.getOriginalFilename();
            if (!StringUtils.hasText(fileName)) {
                fileName = "upload.bin";
            }
            String mimeType = MimeTypeDetector.detect(bytes, fileName);
            DocumentSource source = DocumentSource.builder()
                    .type(SourceType.FILE)
                    .location(fileName)
                    .fileName(fileName)
                    .build();
            IngestionResult result = executeInternal(pipelineId, source, bytes, mimeType, null);
            putTaskSnapshot(result);
            return result;
        } catch (ClientException e) {
            throw e;
        } catch (Exception e) {
            throw new ClientException("读取上传文件失败: " + e.getMessage());
        }
    }

    /**
     * 获取任务详情
     */
    @Override
    public IngestionTaskVO get(String taskId) {
        IngestionTaskDO task = taskMapper.selectById(taskId);
        Assert.notNull(task, () -> new ClientException("未找到任务"));
        return toVO(task);
    }

    /**
     * 分页查询任务，支持按状态筛选
     */
    @Override
    public IPage<IngestionTaskVO> page(Page<IngestionTaskVO> page, String status) {
        Page<IngestionTaskDO> mpPage = new Page<>(page.getCurrent(), page.getSize());
        String normalizedStatus = normalizeStatus(status);
        LambdaQueryWrapper<IngestionTaskDO> qw = new LambdaQueryWrapper<IngestionTaskDO>()
                .eq(IngestionTaskDO::getDeleted, 0)
                .eq(StringUtils.hasText(normalizedStatus), IngestionTaskDO::getStatus, normalizedStatus)
                .orderByDesc(IngestionTaskDO::getCreateTime);
        IPage<IngestionTaskDO> result = taskMapper.selectPage(mpPage, qw);
        Page<IngestionTaskVO> voPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        voPage.setRecords(result.getRecords().stream().map(this::toVO).toList());
        return voPage;
    }

    /**
     * 按节点顺序获取任务的节点运行记录
     */
    @Override
    public List<IngestionTaskNodeVO> listNodes(String taskId) {
        LambdaQueryWrapper<IngestionTaskNodeDO> qw = new LambdaQueryWrapper<IngestionTaskNodeDO>()
                .eq(IngestionTaskNodeDO::getDeleted, 0)
                .eq(IngestionTaskNodeDO::getTaskId, taskId)
                .orderByAsc(IngestionTaskNodeDO::getNodeOrder)
                .orderByAsc(IngestionTaskNodeDO::getId);
        List<IngestionTaskNodeDO> nodes = taskNodeMapper.selectList(qw);
        return nodes.stream().map(this::toNodeVO).toList();
    }

    /**
     * 任务执行内部实现
     *
     * <p>先落库一条 RUNNING 状态的任务记录，构建 IngestionContext 后交由引擎
     * 沿流水线执行；执行结束后保存节点日志并回写任务状态、分块数量等结果。
     */
    private IngestionResult executeInternal(String pipelineId,
                                            DocumentSource source,
                                            byte[] rawBytes,
                                            String mimeType,
                                            VectorSpaceId vectorSpaceId) {
        String resolvedPipelineId = resolvePipelineId(pipelineId);
        PipelineDefinition pipeline = pipelineService.getDefinition(resolvedPipelineId);

        IngestionTaskDO task = IngestionTaskDO.builder()
                .pipelineId(resolvedPipelineId)
                .sourceType(source.getType() == null ? null : source.getType().getValue())
                .sourceLocation(source.getLocation())
                .sourceFileName(source.getFileName())
                .status(IngestionStatus.RUNNING.getValue())
                .chunkCount(0)
                .startedAt(new Date())
                .createdBy(UserContext.getUsername())
                .updatedBy(UserContext.getUsername())
                .build();
        taskMapper.insert(task);

        IngestionContext context = IngestionContext.builder()
                .taskId(String.valueOf(task.getId()))
                .pipelineId(resolvedPipelineId)
                .source(source)
                .rawBytes(rawBytes)
                .mimeType(mimeType)
                .vectorSpaceId(vectorSpaceId)
                .logs(new ArrayList<>())
                .build();

        IngestionContext result = engine.execute(pipeline, context);
        saveNodeLogs(task, pipeline, result.getLogs());
        updateTaskFromContext(task, result);
        return IngestionResult.builder()
                .taskId(result.getTaskId())
                .pipelineId(result.getPipelineId())
                .status(result.getStatus())
                .chunkCount(result.getChunks() == null ? 0 : result.getChunks().size())
                .message(result.getError() == null ? "OK" : result.getError().getMessage())
                .build();
    }

    /**
     * 将任务执行结果快照写入变更审计上下文，无任务 ID 时跳过记录
     */
    private void putTaskSnapshot(IngestionResult result) {
        if (result == null || !StringUtils.hasText(result.getTaskId())) {
            bizChangeLogContext.skip();
            return;
        }
        bizChangeLogContext.put(result.getTaskId(), null, taskMapper.selectById(result.getTaskId()));
    }

    /**
     * 根据引擎执行上下文回写任务状态、分块数量、错误信息与日志摘要
     *
     * <p>上下文无状态时按 FAILED 标记，保证异常任务也能落终态。
     */
    private void updateTaskFromContext(IngestionTaskDO task, IngestionContext context) {
        task.setStatus(context.getStatus() == null ? IngestionStatus.FAILED.getValue() : context.getStatus().getValue());
        task.setChunkCount(context.getChunks() == null ? 0 : context.getChunks().size());
        task.setErrorMessage(context.getError() == null ? null : context.getError().getMessage());
        task.setCompletedAt(new Date());
        task.setUpdatedBy(UserContext.getUsername());
        task.setLogsJson(writeJson(buildLogSummary(context.getLogs())));
        task.setMetadataJson(writeJson(buildTaskMetadata(context)));
        taskMapper.updateById(task);
    }

    /**
     * 逐条保存任务节点运行日志，输出过大时截断后落库
     */
    private void saveNodeLogs(IngestionTaskDO task, PipelineDefinition pipeline, List<NodeLog> logs) {
        if (logs == null || logs.isEmpty()) {
            return;
        }
        Map<String, Integer> nodeOrderMap = buildNodeOrderMap(pipeline);
        for (NodeLog log : logs) {
            String status = resolveNodeStatus(log);
            String outputJson = truncateOutputJson(log.getOutput());
            IngestionTaskNodeDO nodeDO = IngestionTaskNodeDO.builder()
                    .taskId(task.getId())
                    .pipelineId(task.getPipelineId())
                    .nodeId(log.getNodeId())
                    .nodeType(log.getNodeType())
                    .nodeOrder(nodeOrderMap.getOrDefault(log.getNodeId(), 0))
                    .status(status)
                    .durationMs(log.getDurationMs())
                    .message(log.getMessage())
                    .errorMessage(log.getError())
                    .outputJson(outputJson)
                    .build();
            taskNodeMapper.insert(nodeDO);
        }
    }

    /**
     * 依据 nextNodeId 链表结构推导各节点的执行顺序
     *
     * <p>以未被引用的节点为起点沿链遍历编号，环外节点追加在末尾，
     * 保证节点运行记录可按执行顺序展示。
     */
    private Map<String, Integer> buildNodeOrderMap(PipelineDefinition pipeline) {
        Map<String, Integer> orderMap = new HashMap<>();
        if (pipeline == null || pipeline.getNodes() == null || pipeline.getNodes().isEmpty()) {
            return orderMap;
        }
        Map<String, NodeConfig> nodeMap = new LinkedHashMap<>();
        for (NodeConfig node : pipeline.getNodes()) {
            if (node == null || !StringUtils.hasText(node.getNodeId())) {
                continue;
            }
            nodeMap.putIfAbsent(node.getNodeId(), node);
        }
        if (nodeMap.isEmpty()) {
            return orderMap;
        }
        Set<String> referenced = new HashSet<>();
        for (NodeConfig node : nodeMap.values()) {
            if (StringUtils.hasText(node.getNextNodeId())) {
                referenced.add(node.getNextNodeId());
            }
        }
        int order = 1;
        Set<String> visited = new HashSet<>();
        for (String nodeId : nodeMap.keySet()) {
            if (referenced.contains(nodeId)) {
                continue;
            }
            String current = nodeId;
            while (StringUtils.hasText(current) && !visited.contains(current)) {
                orderMap.put(current, order++);
                visited.add(current);
                NodeConfig config = nodeMap.get(current);
                if (config == null) {
                    break;
                }
                current = config.getNextNodeId();
            }
        }
        for (String nodeId : nodeMap.keySet()) {
            if (!visited.contains(nodeId)) {
                orderMap.put(nodeId, order++);
            }
        }
        return orderMap;
    }

    /**
     * 解析节点日志状态：失败、跳过或成功
     */
    private String resolveNodeStatus(NodeLog log) {
        if (log == null) {
            return "failed";
        }
        if (!log.isSuccess()) {
            return "failed";
        }
        String message = log.getMessage();
        if (message != null && message.startsWith("Skipped:")) {
            return "skipped";
        }
        return "success";
    }

    /**
     * 汇总任务元数据，包含上下文 metadata 及增强产出的 keywords、questions
     */
    private Map<String, Object> buildTaskMetadata(IngestionContext context) {
        Map<String, Object> data = new HashMap<>();
        if (context.getMetadata() != null) {
            data.putAll(context.getMetadata());
        }
        if (context.getKeywords() != null && !context.getKeywords().isEmpty()) {
            data.put("keywords", context.getKeywords());
        }
        if (context.getQuestions() != null && !context.getQuestions().isEmpty()) {
            data.put("questions", context.getQuestions());
        }
        return data;
    }

    /**
     * 解析流水线 ID，未传入时抛出客户端异常
     */
    private String resolvePipelineId(String pipelineId) {
        if (StringUtils.hasText(pipelineId)) {
            return pipelineId;
        }
        throw new ClientException("必须传流水线ID");
    }

    /**
     * 规范化任务状态，非法状态值原样返回
     */
    private String normalizeStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return status;
        }
        try {
            return IngestionStatus.fromValue(status).getValue();
        } catch (IllegalArgumentException ex) {
            return status;
        }
    }

    /**
     * 文档来源请求转 DocumentSource，来源类型不能为空
     */
    private DocumentSource toSource(DocumentSourceRequest request) {
        Assert.notNull(request, () -> new ClientException("文档来源不能为空"));
        DocumentSource source = DocumentSource.builder()
                .type(request.getType())
                .location(request.getLocation())
                .fileName(request.getFileName())
                .credentials(request.getCredentials())
                .build();
        if (source.getType() == null) {
            throw new ClientException("文档来源类型不能为空");
        }
        return source;
    }

    /**
     * 任务 DO 转 VO，日志与元数据 JSON 反序列化后填充
     */
    private IngestionTaskVO toVO(IngestionTaskDO task) {
        return IngestionTaskVO.builder()
                .id(String.valueOf(task.getId()))
                .pipelineId(String.valueOf(task.getPipelineId()))
                .sourceType(normalizeSourceType(task.getSourceType()))
                .sourceLocation(task.getSourceLocation())
                .sourceFileName(task.getSourceFileName())
                .status(normalizeStatus(task.getStatus()))
                .chunkCount(task.getChunkCount())
                .errorMessage(task.getErrorMessage())
                .logs(readLogs(task.getLogsJson()))
                .metadata(readMap(task.getMetadataJson()))
                .startedAt(task.getStartedAt())
                .completedAt(task.getCompletedAt())
                .createdBy(task.getCreatedBy())
                .createTime(task.getCreateTime())
                .updateTime(task.getUpdateTime())
                .build();
    }

    /**
     * 任务节点 DO 转 VO，输出 JSON 反序列化为 Map
     */
    private IngestionTaskNodeVO toNodeVO(IngestionTaskNodeDO node) {
        return IngestionTaskNodeVO.builder()
                .id(String.valueOf(node.getId()))
                .taskId(String.valueOf(node.getTaskId()))
                .pipelineId(String.valueOf(node.getPipelineId()))
                .nodeId(node.getNodeId())
                .nodeType(normalizeNodeType(node.getNodeType()))
                .nodeOrder(node.getNodeOrder())
                .status(normalizeNodeStatus(node.getStatus()))
                .durationMs(node.getDurationMs())
                .message(node.getMessage())
                .errorMessage(node.getErrorMessage())
                .output(readMap(node.getOutputJson()))
                .createTime(node.getCreateTime())
                .updateTime(node.getUpdateTime())
                .build();
    }

    /**
     * 对象序列化为 JSON 字符串，失败返回 null
     */
    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * JSON 字符串反序列化为 Map，空值或失败时返回空 Map
     */
    private Map<String, Object> readMap(String raw) {
        if (!StringUtils.hasText(raw)) {
            return Map.of();
        }
        try {
            Map<String, Object> value = objectMapper.readValue(raw, new TypeReference<Map<String, Object>>() {
            });
            return value == null ? Map.of() : value;
        } catch (Exception e) {
            return Map.of();
        }
    }

    /**
     * 构建去除 output 明细的日志摘要，避免任务记录过大
     */
    private List<NodeLog> buildLogSummary(List<NodeLog> logs) {
        if (logs == null) {
            return List.of();
        }
        return logs.stream()
                .map(log -> NodeLog.builder()
                        .nodeId(log.getNodeId())
                        .nodeType(log.getNodeType())
                        .message(log.getMessage())
                        .durationMs(log.getDurationMs())
                        .success(log.isSuccess())
                        .error(log.getError())
                        .output(null)
                        .build())
                .toList();
    }

    /**
     * 反序列化任务日志 JSON，空值或失败时返回空列表
     */
    private List<NodeLog> readLogs(String raw) {
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(raw, new TypeReference<List<NodeLog>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * 规范化文档来源类型，非法值原样返回
     */
    private String normalizeSourceType(String sourceType) {
        if (!StringUtils.hasText(sourceType)) {
            return sourceType;
        }
        try {
            return SourceType.fromValue(sourceType).getValue();
        } catch (IllegalArgumentException ex) {
            return sourceType;
        }
    }

    /**
     * 规范化节点类型，非法值原样返回
     */
    private String normalizeNodeType(String nodeType) {
        if (!StringUtils.hasText(nodeType)) {
            return nodeType;
        }
        try {
            return IngestionNodeType.fromValue(nodeType).getValue();
        } catch (IllegalArgumentException ex) {
            return nodeType;
        }
    }

    /**
     * 规范化节点状态展示值，统一小写并将连字符转为下划线
     */
    private String normalizeNodeStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return status;
        }
        String trimmed = status.trim();
        String lower = trimmed.toLowerCase();
        return lower.replace('-', '_');
    }

    /**
     * 截断过大的输出 JSON，防止超过数据库的 max_allowed_packet 限制
     * 默认限制为 1MB
     */
    private String truncateOutputJson(Object output) {
        if (output == null) {
            return null;
        }
        String json = writeJson(output);
        if (json == null) {
            return null;
        }
        // 限制为 1MB (1,048,576 字节)，留有余量避免接近 4MB 上限
        int maxSize = 1024 * 1024;
        if (json.length() <= maxSize) {
            return json;
        }
        // 截断并添加提示信息
        String truncated = json.substring(0, maxSize - 100);
        return truncated + "... [输出过大，已截断，原始大小: " + json.length() + " 字节]";
    }
}
