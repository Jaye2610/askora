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

package io.github.jaye.ai.askora.rag.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.jaye.ai.askora.rag.dao.entity.MessageFeedbackDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

/**
 * 会话消息反馈 Mapper
 */
public interface MessageFeedbackMapper extends BaseMapper<MessageFeedbackDO> {

    /**
     * 写入点赞/踩反馈；同一用户对同一消息重复反馈时按更新时间较新者覆盖。
     */
    @Insert("""
            INSERT INTO t_message_feedback
                (id, message_id, conversation_id, user_id, vote, reason, comment, create_time, update_time, deleted)
            VALUES
                (#{feedback.id}, #{feedback.messageId}, #{feedback.conversationId}, #{feedback.userId},
                 #{feedback.vote}, #{feedback.reason}, #{feedback.comment},
                 #{feedback.createTime}, #{feedback.updateTime}, 0)
            ON CONFLICT (message_id, user_id) DO UPDATE SET
                conversation_id = EXCLUDED.conversation_id,
                vote = EXCLUDED.vote,
                reason = EXCLUDED.reason,
                comment = EXCLUDED.comment,
                update_time = EXCLUDED.update_time,
                deleted = 0
            WHERE t_message_feedback.update_time < EXCLUDED.update_time
            """)
    int upsertActiveFeedback(@Param("feedback") MessageFeedbackDO feedback);

    /**
     * 写入取消反馈；记录不存在时创建逻辑删除占位，保证重复取消幂等。
     */
    @Insert("""
            INSERT INTO t_message_feedback
                (id, message_id, conversation_id, user_id, vote, reason, comment, create_time, update_time, deleted)
            VALUES
                (#{feedback.id}, #{feedback.messageId}, #{feedback.conversationId}, #{feedback.userId},
                 0, NULL, NULL, #{feedback.createTime}, #{feedback.updateTime}, 1)
            ON CONFLICT (message_id, user_id) DO UPDATE SET
                update_time = EXCLUDED.update_time,
                deleted = 1
            WHERE t_message_feedback.update_time < EXCLUDED.update_time
            """)
    int upsertCancelledFeedback(@Param("feedback") MessageFeedbackDO feedback);
}
