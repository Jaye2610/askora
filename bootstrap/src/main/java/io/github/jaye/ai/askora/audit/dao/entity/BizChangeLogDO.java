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

package io.github.jaye.ai.askora.audit.dao.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.github.jaye.ai.askora.knowledge.dao.handler.JsonbTypeHandler;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 业务变更审计日志实体（t_biz_change_log）
 * <p>
 * 记录一次管理端对核心业务对象（知识库、文档、意图树等）的增删改操作：
 * 记录变更前后快照与 Diff、操作人信息、执行结果与方法调用来源，
 * 供管理后台审计查询与配置变更溯源。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "t_biz_change_log", autoResultMap = true)
public class BizChangeLogDO {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    /** 业务类型（如知识库、文档、意图树），对应 BizChangeBizType */
    private String bizType;

    /** 被变更业务对象的主键 ID */
    private String bizId;

    /** 操作类型（新增/修改/删除等），对应 BizChangeOperationType */
    private String operationType;

    /** 操作内容的人读描述（引用业务对象名称，如文档名） */
    private String actionDesc;

    /** 变更前快照（JSON 序列化字段） */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String beforeSnapshot;

    /** 变更后快照（JSON 序列化字段） */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String afterSnapshot;

    /** 变更前后 Diff（JSON 序列化字段） */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String changeDiff;

    /** 操作人用户 ID */
    private String operatorId;

    /** 操作人姓名 */
    private String operatorName;

    /** 操作人角色（如管理员） */
    private String operatorRole;

    /** 操作是否成功 */
    private Boolean success;

    /** 失败时的错误信息 */
    private String errorMessage;

    /** 发起操作的类名 */
    private String className;

    /** 发起操作的方法名 */
    private String methodName;

    /** 操作来源 IP */
    private String ip;

    /** 操作来源浏览器信息 */
    private String userAgent;

    @TableField(fill = FieldFill.INSERT)
    private Date createTime;
}
