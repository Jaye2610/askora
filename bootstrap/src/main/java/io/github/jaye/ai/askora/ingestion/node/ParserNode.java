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

package io.github.jaye.ai.askora.ingestion.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jaye.ai.askora.core.parser.BlockTextRenderer;
import io.github.jaye.ai.askora.core.parser.DocumentParser;
import io.github.jaye.ai.askora.core.parser.model.Block;
import io.github.jaye.ai.askora.core.parser.model.ParsedDocument;
import io.github.jaye.ai.askora.core.parser.registry.ParseProfile;
import io.github.jaye.ai.askora.core.parser.registry.ParserRegistry;
import io.github.jaye.ai.askora.framework.exception.ClientException;
import io.github.jaye.ai.askora.ingestion.domain.context.IngestionContext;
import io.github.jaye.ai.askora.ingestion.domain.context.StructuredDocument;
import io.github.jaye.ai.askora.ingestion.domain.enums.IngestionNodeType;
import io.github.jaye.ai.askora.ingestion.domain.pipeline.NodeConfig;
import io.github.jaye.ai.askora.ingestion.domain.result.NodeResult;
import io.github.jaye.ai.askora.ingestion.domain.settings.ParserSettings;
import io.github.jaye.ai.askora.core.parser.mime.MimeTypeDetector;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档解析节点
 * 负责将输入的字节流（如 PDF、Word、Excel 等）解析为结构化的文本或文档对象
 */
@Component
public class ParserNode implements IngestionNode {

    private final ObjectMapper objectMapper;
    private final ParserRegistry parserRegistry;

    public ParserNode(ObjectMapper objectMapper, ParserRegistry parserRegistry) {
        this.objectMapper = objectMapper;
        this.parserRegistry = parserRegistry;
    }

    /**
     * 获取 PARSER 节点类型标识
     */
    @Override
    public String getNodeType() {
        return IngestionNodeType.PARSER.getValue();
    }

    /**
     * 把原始字节解析为结构化文档
     * <p>
     * 依次完成：MIME 探测（缺失时补）、按配置规则校验文件类型、匹配解析规则取选项、
     * 按 (MIME × 档位) 查注册表定位解析器；解析出的 Block 列表与渲染后的纯文本
     * 一并写入上下文——结构化 Block 供分块用，纯文本给老路径兜底
     */
    @Override
    public NodeResult execute(IngestionContext context, NodeConfig config) {
        if (context.getRawBytes() == null || context.getRawBytes().length == 0) {
            return NodeResult.fail(new ClientException("解析器缺少原始字节"));
        }

        String mimeType = context.getMimeType();
        if (!StringUtils.hasText(mimeType)) {
            String fileName = context.getSource() == null ? null : context.getSource().getFileName();
            mimeType = MimeTypeDetector.detect(context.getRawBytes(), fileName);
            context.setMimeType(mimeType);
        }

        ParserSettings settings = parseSettings(config.getSettings());
        String fileName = context.getSource() == null ? null : context.getSource().getFileName();

        // 验证文件类型是否符合配置
        validateMimeType(settings, mimeType, fileName);

        ParserSettings.ParserRule rule = matchRule(settings, mimeType, fileName);

        // 按 (MIME × 档位) 查注册表；不匹配显式抛错，不静默兜底
        // 管道暂无档位入口，走默认档；档位随 IngestionSpec 下发是内核化之后的事
        DocumentParser parser = parserRegistry.find(mimeType, ParseProfile.defaultProfile()).orElse(null);
        if (parser == null) {
            return NodeResult.fail(new ClientException(
                    "未找到 MIME [" + mimeType + "] 对应的解析器,fileName=" + fileName));
        }

        Map<String, Object> ruleOptions = rule == null ? null : rule.getOptions();
        Map<String, Object> options = new HashMap<>(ruleOptions != null ? ruleOptions : Collections.emptyMap());

        // 把 sourceFile 注入 options，供解析器写入 Provenance.sourceFile
        if (StringUtils.hasText(fileName) && !options.containsKey("sourceFile")) {
            options.put("sourceFile", fileName);
        }

        // 把 documentId(=taskId)注入 options，供解析器做资产 key 命名 assets/{documentId}/...
        // 图片解析必需，MinerU 抽图同样受益(资产稳定归属文档目录，不再落随机 UUID)
        if (StringUtils.hasText(context.getTaskId()) && !options.containsKey("documentId")) {
            options.put("documentId", context.getTaskId());
        }

        // v1.1：调 parseStructured 拿结构化 Block 列表
        ParsedDocument parsed = parser.parseStructured(context.getRawBytes(), mimeType, options);
        List<Block> blocks = parsed.blocks() == null ? List.of() : parsed.blocks();

        // 从 blocks 渲染纯文本（给老路径 / ChunkerNode fallback 用）
        String renderedText = BlockTextRenderer.render(blocks);
        context.setRawText(renderedText);

        StructuredDocument document = StructuredDocument.builder()
                .text(renderedText)
                .blocks(blocks)
                .metadata(parsed.metadata())
                .build();
        context.setDocument(document);

        return NodeResult.ok(String.format("解析器=%s, blocks=%d, 文本长度=%d",
                parser.getParserType(), blocks.size(), renderedText.length()));
    }

    /**
     * 验证文件类型是否符合配置的规则
     * 如果配置了规则但文件类型不匹配，则抛出异常
     */
    private void validateMimeType(ParserSettings settings, String mimeType, String fileName) {
        if (settings == null || settings.getRules() == null || settings.getRules().isEmpty()) {
            // 没有配置规则，允许所有类型
            return;
        }

        String resolvedType = resolveType(mimeType, fileName);

        // 检查是否有匹配的规则
        boolean hasMatch = false;
        for (ParserSettings.ParserRule rule : settings.getRules()) {
            if (rule == null || !StringUtils.hasText(rule.getMimeType())) {
                continue;
            }
            String configured = normalizeType(rule.getMimeType());
            if (!StringUtils.hasText(configured)) {
                continue;
            }
            if ("ALL".equals(configured) || configured.equalsIgnoreCase(resolvedType)) {
                hasMatch = true;
                break;
            }
        }

        if (!hasMatch) {
            // 构建允许的类型列表用于错误提示
            List<String> allowedTypes = settings.getRules().stream()
                    .filter(rule -> rule != null && StringUtils.hasText(rule.getMimeType()))
                    .map(rule -> normalizeType(rule.getMimeType()))
                    .filter(StringUtils::hasText)
                    .distinct()
                    .toList();

            throw new ClientException(
                    String.format("文件类型不符合要求。当前文件类型: %s，允许的类型: %s",
                            resolvedType,
                            String.join(", ", allowedTypes))
            );
        }
    }

    /**
     * 把节点配置里的 JSON settings 反序列化为解析器配置，缺失时给空规则兜底
     */
    private ParserSettings parseSettings(JsonNode node) {
        if (node == null || node.isNull()) {
            return ParserSettings.builder().rules(List.of()).build();
        }
        return objectMapper.convertValue(node, ParserSettings.class);
    }

    /**
     * 按文件类型匹配第一条命中的解析规则，取其选项作为解析器参数；无规则配置返回 null
     */
    private ParserSettings.ParserRule matchRule(ParserSettings settings, String mimeType, String fileName) {
        if (settings == null || settings.getRules() == null || settings.getRules().isEmpty()) {
            return null;
        }
        String resolvedType = resolveType(mimeType, fileName);
        for (ParserSettings.ParserRule rule : settings.getRules()) {
            if (rule == null || !StringUtils.hasText(rule.getMimeType())) {
                continue;
            }
            String configured = normalizeType(rule.getMimeType());
            if (!StringUtils.hasText(configured)) {
                continue;
            }
            if ("ALL".equals(configured) || configured.equalsIgnoreCase(resolvedType)) {
                return rule;
            }
        }
        return null;
    }

    /**
     * 归一化文件类型：优先按文件名后缀判定，其次按 MIME 关键字归类（PDF/MARKDOWN/WORD/EXCEL/PPT/IMAGE/TEXT），
     * 两者都判不出返回 UNKNOWN
     */
    private String resolveType(String mimeType, String fileName) {
        String byName = resolveTypeByName(fileName);
        if (StringUtils.hasText(byName)) {
            return byName;
        }
        if (!StringUtils.hasText(mimeType)) {
            return "UNKNOWN";
        }
        String lower = mimeType.trim().toLowerCase();
        if (lower.contains("pdf")) {
            return "PDF";
        }
        if (lower.contains("markdown")) {
            return "MARKDOWN";
        }
        if (lower.contains("word") || lower.contains("msword") || lower.contains("wordprocessingml")) {
            return "WORD";
        }
        if (lower.contains("excel") || lower.contains("spreadsheetml")) {
            return "EXCEL";
        }
        if (lower.contains("powerpoint") || lower.contains("presentation")) {
            return "PPT";
        }
        if (lower.startsWith("image/")) {
            return "IMAGE";
        }
        if (lower.startsWith("text/")) {
            return "TEXT";
        }
        return "UNKNOWN";
    }

    /**
     * 按文件扩展名判定类型，判不出返回 null 交由 MIME 兜底
     */
    private String resolveTypeByName(String fileName) {
        if (!StringUtils.hasText(fileName)) {
            return null;
        }
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".pdf")) {
            return "PDF";
        }
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) {
            return "MARKDOWN";
        }
        if (lower.endsWith(".doc") || lower.endsWith(".docx")) {
            return "WORD";
        }
        if (lower.endsWith(".xls") || lower.endsWith(".xlsx")) {
            return "EXCEL";
        }
        if (lower.endsWith(".ppt") || lower.endsWith(".pptx")) {
            return "PPT";
        }
        if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".gif") || lower.endsWith(".bmp") || lower.endsWith(".webp")) {
            return "IMAGE";
        }
        if (lower.endsWith(".txt")) {
            return "TEXT";
        }
        return null;
    }

    /**
     * 归一化配置里写的类型别名：* / ALL / DEFAULT 统一为 ALL，各格式的常见别名归一到标准类型名
     */
    private String normalizeType(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String value = raw.trim().toUpperCase();
        return switch (value) {
            case "*", "ALL", "DEFAULT" -> "ALL";
            case "MD", "MARKDOWN" -> "MARKDOWN";
            case "DOC", "DOCX", "WORD" -> "WORD";
            case "XLS", "XLSX", "EXCEL" -> "EXCEL";
            case "PPT", "PPTX", "POWERPOINT" -> "PPT";
            case "TXT", "TEXT" -> "TEXT";
            case "PNG", "JPG", "JPEG", "GIF", "BMP", "WEBP", "IMAGE", "IMG" -> "IMAGE";
            case "PDF" -> "PDF";
            default -> value;
        };
    }
}
