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

package io.github.jaye.ai.askora.ingestion.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.jaye.ai.askora.ingestion.dao.entity.IngestionPipelineNodeDO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

/**
 * 摄取流水线节点 Mapper
 */
public interface IngestionPipelineNodeMapper extends BaseMapper<IngestionPipelineNodeDO> {

    /**
     * 根据流水线 ID 物理删除其下所有节点
     */
    @Delete("DELETE FROM t_ingestion_pipeline_node WHERE pipeline_id = #{pipelineId}")
    int physicalDeleteByPipelineId(@Param("pipelineId") String pipelineId);
}
