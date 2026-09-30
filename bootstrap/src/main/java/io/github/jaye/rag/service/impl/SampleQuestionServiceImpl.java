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
import io.github.jaye.ai.askora.rag.controller.request.SampleQuestionCreateRequest;
import io.github.jaye.ai.askora.rag.controller.request.SampleQuestionPageRequest;
import io.github.jaye.ai.askora.rag.controller.request.SampleQuestionUpdateRequest;
import io.github.jaye.ai.askora.rag.controller.vo.SampleQuestionVO;
import io.github.jaye.ai.askora.rag.dao.entity.SampleQuestionDO;
import io.github.jaye.ai.askora.rag.dao.mapper.SampleQuestionMapper;
import io.github.jaye.ai.askora.rag.service.SampleQuestionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 示例问题服务实现类
 * 负责示例问题的增删改查，写操作通过 LogRecord 记录业务变更审计日志
 */
@Service
@RequiredArgsConstructor
public class SampleQuestionServiceImpl implements SampleQuestionService {

    private static final int DEFAULT_LIMIT = 3;

    private final SampleQuestionMapper sampleQuestionMapper;
    private final BizChangeLogContext bizChangeLogContext;

    /**
     * 创建示例问题并记录审计快照
     */
    @Override
    @LogRecord(
            success = "创建示例问题：{{#requestParam.question}}",
            fail = "创建示例问题失败：{{#_errorMsg}}",
            type = BizChangeBizType.SAMPLE_QUESTION,
            subType = BizChangeOperationType.CREATE,
            bizNo = BizChangeLogContext.BIZ_ID_EXPRESSION,
            extra = BizChangeLogContext.SNAPSHOT_EXPRESSION,
            condition = BizChangeLogContext.RECORD_CONDITION
    )
    public String create(SampleQuestionCreateRequest requestParam) {
        Assert.notNull(requestParam, () -> new ClientException("请求不能为空"));
        String question = StrUtil.trimToNull(requestParam.getQuestion());
        Assert.notBlank(question, () -> new ClientException("示例问题内容不能为空"));

        SampleQuestionDO record = SampleQuestionDO.builder()
                .title(StrUtil.trimToNull(requestParam.getTitle()))
                .description(StrUtil.trimToNull(requestParam.getDescription()))
                .question(question)
                .build();
        sampleQuestionMapper.insert(record);
        bizChangeLogContext.put(String.valueOf(record.getId()), null, record);
        return String.valueOf(record.getId());
    }

    /**
     * 更新示例问题，仅覆盖请求中非空字段并记录变更前后快照
     */
    @Override
    @LogRecord(
            success = "更新示例问题：{{#id}}",
            fail = "更新示例问题失败：{{#_errorMsg}}",
            type = BizChangeBizType.SAMPLE_QUESTION,
            subType = BizChangeOperationType.UPDATE,
            bizNo = "{{#id}}",
            extra = BizChangeLogContext.SNAPSHOT_EXPRESSION,
            condition = BizChangeLogContext.RECORD_CONDITION
    )
    public void update(String id, SampleQuestionUpdateRequest requestParam) {
        Assert.notNull(requestParam, () -> new ClientException("请求不能为空"));
        SampleQuestionDO record = loadById(id);
        SampleQuestionDO before = BeanUtil.copyProperties(record, SampleQuestionDO.class);

        if (requestParam.getQuestion() != null) {
            String question = StrUtil.trimToNull(requestParam.getQuestion());
            Assert.notBlank(question, () -> new ClientException("示例问题内容不能为空"));
            record.setQuestion(question);
        }
        if (requestParam.getTitle() != null) {
            record.setTitle(StrUtil.trimToNull(requestParam.getTitle()));
        }
        if (requestParam.getDescription() != null) {
            record.setDescription(StrUtil.trimToNull(requestParam.getDescription()));
        }

        sampleQuestionMapper.updateById(record);
        bizChangeLogContext.put(id, before, sampleQuestionMapper.selectById(id));
    }

    /**
     * 删除示例问题并记录删除前快照
     */
    @Override
    @LogRecord(
            success = "删除示例问题：{{#id}}",
            fail = "删除示例问题失败：{{#_errorMsg}}",
            type = BizChangeBizType.SAMPLE_QUESTION,
            subType = BizChangeOperationType.DELETE,
            bizNo = "{{#id}}",
            extra = BizChangeLogContext.SNAPSHOT_EXPRESSION,
            condition = BizChangeLogContext.RECORD_CONDITION
    )
    public void delete(String id) {
        SampleQuestionDO record = loadById(id);
        SampleQuestionDO before = BeanUtil.copyProperties(record, SampleQuestionDO.class);
        sampleQuestionMapper.deleteById(record.getId());
        bizChangeLogContext.put(id, before, null);
    }

    /**
     * 查询单个示例问题详情
     */
    @Override
    public SampleQuestionVO queryById(String id) {
        SampleQuestionDO record = loadById(id);
        return toVO(record);
    }

    /**
     * 按关键词模糊匹配分页查询示例问题，按更新时间倒序
     */
    @Override
    public IPage<SampleQuestionVO> pageQuery(SampleQuestionPageRequest requestParam) {
        String keyword = StrUtil.trimToNull(requestParam.getKeyword());
        Page<SampleQuestionDO> page = new Page<>(requestParam.getCurrent(), requestParam.getSize());
        IPage<SampleQuestionDO> result = sampleQuestionMapper.selectPage(
                page,
                Wrappers.lambdaQuery(SampleQuestionDO.class)
                        .eq(SampleQuestionDO::getDeleted, 0)
                        .and(StrUtil.isNotBlank(keyword), wrapper -> wrapper
                                .like(SampleQuestionDO::getTitle, keyword)
                                .or()
                                .like(SampleQuestionDO::getDescription, keyword)
                                .or()
                                .like(SampleQuestionDO::getQuestion, keyword))
                        .orderByDesc(SampleQuestionDO::getUpdateTime)
        );
        return result.convert(this::toVO);
    }

    /**
     * 随机抽取若干条示例问题，用于对话首页推荐展示
     */
    @Override
    public List<SampleQuestionVO> listRandomQuestions() {
        List<SampleQuestionDO> records = sampleQuestionMapper.selectList(
                Wrappers.lambdaQuery(SampleQuestionDO.class)
                        .eq(SampleQuestionDO::getDeleted, 0)
                        .last("ORDER BY RANDOM() LIMIT " + DEFAULT_LIMIT)
        );
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        return records.stream()
                .map(this::toVO)
                .toList();
    }

    /**
     * 按主键加载未删除记录，不存在抛业务异常
     */
    private SampleQuestionDO loadById(String id) {
        SampleQuestionDO record = sampleQuestionMapper.selectOne(
                Wrappers.lambdaQuery(SampleQuestionDO.class)
                        .eq(SampleQuestionDO::getId, id)
                        .eq(SampleQuestionDO::getDeleted, 0)
        );
        Assert.notNull(record, () -> new ClientException("示例问题不存在"));
        return record;
    }

    /**
     * DO 转 VO
     */
    private SampleQuestionVO toVO(SampleQuestionDO record) {
        return SampleQuestionVO.builder()
                .id(String.valueOf(record.getId()))
                .title(record.getTitle())
                .description(record.getDescription())
                .question(record.getQuestion())
                .createTime(record.getCreateTime())
                .updateTime(record.getUpdateTime())
                .build();
    }
}
