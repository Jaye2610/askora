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

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mzt.logapi.starter.annotation.LogRecord;
import io.github.jaye.ai.askora.audit.constant.BizChangeBizType;
import io.github.jaye.ai.askora.audit.constant.BizChangeOperationType;
import io.github.jaye.ai.askora.audit.support.BizChangeLogContext;
import io.github.jaye.ai.askora.framework.exception.ClientException;
import io.github.jaye.ai.askora.rag.controller.request.QueryTermMappingCreateRequest;
import io.github.jaye.ai.askora.rag.controller.request.QueryTermMappingPageRequest;
import io.github.jaye.ai.askora.rag.controller.request.QueryTermMappingUpdateRequest;
import io.github.jaye.ai.askora.rag.controller.vo.QueryTermMappingVO;
import io.github.jaye.ai.askora.rag.core.rewrite.QueryTermMappingCacheManager;
import io.github.jaye.ai.askora.rag.dao.entity.QueryTermMappingDO;
import io.github.jaye.ai.askora.rag.dao.mapper.QueryTermMappingMapper;
import io.github.jaye.ai.askora.rag.service.QueryTermMappingAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 查询词映射管理服务实现类
 * 维护改写阶段用的关键词映射规则，写操作后统一清空映射缓存
 */
@Service
@RequiredArgsConstructor
public class QueryTermMappingAdminServiceImpl implements QueryTermMappingAdminService {

    private final QueryTermMappingMapper queryTermMappingMapper;
    private final QueryTermMappingCacheManager queryTermMappingCacheManager;
    private final BizChangeLogContext bizChangeLogContext;

    /**
     * 创建关键词映射规则，参数缺省时按默认值落库并刷新缓存
     */
    @Override
    @LogRecord(
            success = "创建关键词映射：{{#requestParam.sourceTerm}}",
            fail = "创建关键词映射失败：{{#_errorMsg}}",
            type = BizChangeBizType.QUERY_TERM_MAPPING,
            subType = BizChangeOperationType.CREATE,
            bizNo = BizChangeLogContext.BIZ_ID_EXPRESSION,
            extra = BizChangeLogContext.SNAPSHOT_EXPRESSION,
            condition = BizChangeLogContext.RECORD_CONDITION
    )
    public String create(QueryTermMappingCreateRequest requestParam) {
        Assert.notNull(requestParam, () -> new ClientException("请求不能为空"));
        String sourceTerm = StrUtil.trimToNull(requestParam.getSourceTerm());
        String targetTerm = StrUtil.trimToNull(requestParam.getTargetTerm());
        Assert.notBlank(sourceTerm, () -> new ClientException("原始词不能为空"));
        Assert.notBlank(targetTerm, () -> new ClientException("目标词不能为空"));

        QueryTermMappingDO record = new QueryTermMappingDO();
        record.setSourceTerm(sourceTerm);
        record.setTargetTerm(targetTerm);
        record.setMatchType(requestParam.getMatchType() != null ? requestParam.getMatchType() : 1);
        record.setPriority(requestParam.getPriority() != null ? requestParam.getPriority() : 0);
        record.setEnabled(requestParam.getEnabled() != null ? (requestParam.getEnabled() ? 1 : 0) : 1);
        record.setRemark(StrUtil.trimToNull(requestParam.getRemark()));

        queryTermMappingMapper.insert(record);
        queryTermMappingCacheManager.clearCache();
        bizChangeLogContext.put(String.valueOf(record.getId()), null, record);
        return String.valueOf(record.getId());
    }

    /**
     * 更新映射规则，仅覆盖请求中非空字段，并记录变更前后快照
     */
    @Override
    @LogRecord(
            success = "更新关键词映射：{{#id}}",
            fail = "更新关键词映射失败：{{#_errorMsg}}",
            type = BizChangeBizType.QUERY_TERM_MAPPING,
            subType = BizChangeOperationType.UPDATE,
            bizNo = "{{#id}}",
            extra = BizChangeLogContext.SNAPSHOT_EXPRESSION,
            condition = BizChangeLogContext.RECORD_CONDITION
    )
    public void update(String id, QueryTermMappingUpdateRequest requestParam) {
        Assert.notNull(requestParam, () -> new ClientException("请求不能为空"));
        QueryTermMappingDO record = loadById(id);
        QueryTermMappingDO before = BeanUtil.copyProperties(record, QueryTermMappingDO.class);

        if (requestParam.getSourceTerm() != null) {
            String sourceTerm = StrUtil.trimToNull(requestParam.getSourceTerm());
            Assert.notBlank(sourceTerm, () -> new ClientException("原始词不能为空"));
            record.setSourceTerm(sourceTerm);
        }
        if (requestParam.getTargetTerm() != null) {
            String targetTerm = StrUtil.trimToNull(requestParam.getTargetTerm());
            Assert.notBlank(targetTerm, () -> new ClientException("目标词不能为空"));
            record.setTargetTerm(targetTerm);
        }
        if (requestParam.getMatchType() != null) {
            record.setMatchType(requestParam.getMatchType());
        }
        if (requestParam.getPriority() != null) {
            record.setPriority(requestParam.getPriority());
        }
        if (requestParam.getEnabled() != null) {
            record.setEnabled(requestParam.getEnabled() ? 1 : 0);
        }
        if (requestParam.getRemark() != null) {
            record.setRemark(StrUtil.trimToNull(requestParam.getRemark()));
        }

        queryTermMappingMapper.updateById(record);
        queryTermMappingCacheManager.clearCache();
        bizChangeLogContext.put(id, before, queryTermMappingMapper.selectById(id));
    }

    /**
     * 删除映射规则并记录删除前快照
     */
    @Override
    @LogRecord(
            success = "删除关键词映射：{{#id}}",
            fail = "删除关键词映射失败：{{#_errorMsg}}",
            type = BizChangeBizType.QUERY_TERM_MAPPING,
            subType = BizChangeOperationType.DELETE,
            bizNo = "{{#id}}",
            extra = BizChangeLogContext.SNAPSHOT_EXPRESSION,
            condition = BizChangeLogContext.RECORD_CONDITION
    )
    public void delete(String id) {
        QueryTermMappingDO record = loadById(id);
        QueryTermMappingDO before = BeanUtil.copyProperties(record, QueryTermMappingDO.class);
        queryTermMappingMapper.deleteById(record.getId());
        queryTermMappingCacheManager.clearCache();
        bizChangeLogContext.put(id, before, null);
    }

    /**
     * 查询单条映射规则详情
     */
    @Override
    public QueryTermMappingVO queryById(String id) {
        QueryTermMappingDO record = loadById(id);
        return toVO(record);
    }

    /**
     * 按关键词模糊匹配分页查询映射规则，按优先级升序、更新时间倒序
     */
    @Override
    public IPage<QueryTermMappingVO> pageQuery(QueryTermMappingPageRequest requestParam) {
        String keyword = StrUtil.trimToNull(requestParam.getKeyword());
        Page<QueryTermMappingDO> page = new Page<>(requestParam.getCurrent(), requestParam.getSize());
        IPage<QueryTermMappingDO> result = queryTermMappingMapper.selectPage(
                page,
                Wrappers.lambdaQuery(QueryTermMappingDO.class)
                        .and(StrUtil.isNotBlank(keyword), wrapper -> wrapper
                                .like(QueryTermMappingDO::getSourceTerm, keyword)
                                .or()
                                .like(QueryTermMappingDO::getTargetTerm, keyword))
                        .orderByAsc(QueryTermMappingDO::getPriority)
                        .orderByDesc(QueryTermMappingDO::getUpdateTime)
        );
        return result.convert(this::toVO);
    }

    /**
     * 按主键加载映射规则，不存在抛业务异常
     */
    private QueryTermMappingDO loadById(String id) {
        QueryTermMappingDO record = queryTermMappingMapper.selectById(id);
        Assert.notNull(record, () -> new ClientException("映射规则不存在"));
        return record;
    }

    /**
     * DO 转 VO
     */
    private QueryTermMappingVO toVO(QueryTermMappingDO record) {
        return QueryTermMappingVO.builder()
                .id(String.valueOf(record.getId()))
                .sourceTerm(record.getSourceTerm())
                .targetTerm(record.getTargetTerm())
                .matchType(record.getMatchType())
                .priority(record.getPriority())
                .enabled(record.getEnabled() != null && record.getEnabled() == 1)
                .remark(record.getRemark())
                .createTime(record.getCreateTime())
                .updateTime(record.getUpdateTime())
                .build();
    }
}
