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

package io.github.jaye.ai.askora.rag.core.rewrite;

import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.jaye.ai.askora.rag.dao.entity.QueryTermMappingDO;
import io.github.jaye.ai.askora.rag.dao.mapper.QueryTermMappingMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 查询术语映射服务
 * <p>
 * 业务语义：把检索/改写阶段送进来的用户原话里的「不稳定措辞」统一替换成「规范术语」。
 * 典型场景是保险行业同义词（如用户说「报销」、库里存的是「理赔」）——先做术语归一化，
 * 再做向量/关键词检索，能让召回更贴近库内标准表达，而不是依赖 LLM 改写去猜。
 * 规则存数据库、热数据缓存在 Redis，支持启用开关、匹配类型与优先级排序。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueryTermMappingService {

    private final QueryTermMappingMapper mappingMapper;
    private final QueryTermMappingCacheManager cacheManager;

    /**
     * 对用户问题做术语归一化
     * <p>
     * 依次套用所有启用且为「精确匹配」的规则做替换，前一条的输出作为下一条的输入，
     * 形成链式归一化；没有任何规则命中时原样返回。
     */
    public String normalize(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        List<QueryTermMappingDO> mappings = loadMappings();
        if (mappings.isEmpty()) {
            return text;
        }

        String result = text;
        for (QueryTermMappingDO mapping : mappings) {
            // 只应用启用(enabled=1)且为精确匹配(matchType=1)的规则，其余类型跳过
            if (mapping.getEnabled() == null || mapping.getEnabled() == 0) {
                continue;
            }
            if (mapping.getMatchType() != null && mapping.getMatchType() != 1) {
                continue;
            }
            String source = mapping.getSourceTerm();
            String target = mapping.getTargetTerm();
            // source/target 任一为空则规则无意义，直接跳过
            if (source == null || source.isEmpty() || target == null || target.isEmpty()) {
                continue;
            }
            // 把当前文本里所有 source 归一化为 target，可能已多次前序替换，故用最新 result
            result = QueryTermMappingUtil.applyMapping(result, source, target);
        }

        if (!Objects.equals(text, result)) {
            log.info("查询归一化：original='{}', normalized='{}'", text, result);
        }
        return result;
    }

    /**
     * 加载映射规则：优先从 Redis 缓存读取，缓存未命中则从数据库加载并回填缓存
     * <p>
     * 从库加载时按优先级降序（priority 大者优先）、同优先级再按 source 词长降序排序，
     * 保证长词/高优先规则先套用，避免短词先替换把长词的命中机会挤掉。
     */
    private List<QueryTermMappingDO> loadMappings() {
        List<QueryTermMappingDO> cached = cacheManager.getMappingsFromCache();
        if (CollUtil.isNotEmpty(cached)) {
            return cached;
        }

        // 缓存未命中，从数据库加载启用中的规则
        List<QueryTermMappingDO> dbList = mappingMapper.selectList(
                Wrappers.lambdaQuery(QueryTermMappingDO.class)
                        .eq(QueryTermMappingDO::getEnabled, 1)
        );
        // 高优先级在前，同优先级按 source 词长降序，词越长越先归一化
        dbList.sort(Comparator
                .comparing(QueryTermMappingDO::getPriority, Comparator.nullsLast(Integer::compareTo)).reversed()
                .thenComparing(m -> m.getSourceTerm() == null ? 0 : m.getSourceTerm().length(), Comparator.reverseOrder())
        );

        // 回填 Redis 缓存，避免每次请求都打库
        cacheManager.saveMappingsToCache(dbList);
        log.info("术语映射规则从数据库加载完成，共 {} 条规则", dbList.size());
        return dbList;
    }
}
