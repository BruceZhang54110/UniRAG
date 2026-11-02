package com.unirag.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.unirag.infrastructure.persistence.entity.ChatMessageEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * @description 针对表【chat_message】的数据库操作Mapper
 * @Entity com.unirag.infrastructure.persistence.entity.ChatMessageEntity
 */
public interface ChatMessageMapper extends BaseMapper<ChatMessageEntity> {

    /** 级联删除（017 FR-008）：删除该会话的全部历史消息。 */
    @Delete("DELETE FROM chat_message WHERE tenant_id = #{tenantId} AND session_id = #{sessionId}")
    int deleteBySession(@Param("tenantId") Long tenantId, @Param("sessionId") String sessionId);

    /** 会话内下一轮的轮次序号（无历史时从 1 起）。 */
    @Select("SELECT COALESCE(MAX(round_seq), 0) + 1 FROM chat_message "
            + "WHERE tenant_id = #{tenantId} AND session_id = #{sessionId}")
    long nextRoundSeq(@Param("tenantId") Long tenantId, @Param("sessionId") String sessionId);

    /** 会话存在性判定（双判之一）：历史行计数。 */
    @Select("SELECT count(*) FROM chat_message WHERE tenant_id = #{tenantId} AND session_id = #{sessionId} LIMIT 1")
    long countBySession(@Param("tenantId") Long tenantId, @Param("sessionId") String sessionId);
}
