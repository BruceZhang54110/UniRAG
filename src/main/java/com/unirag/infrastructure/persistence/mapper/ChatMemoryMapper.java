package com.unirag.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.unirag.infrastructure.persistence.entity.ChatMemoryEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * @description 针对表【chat_memory】的数据库操作Mapper
 * @Entity com.unirag.infrastructure.persistence.entity.ChatMemoryEntity
 */
public interface ChatMemoryMapper extends BaseMapper<ChatMemoryEntity> {

    /** 行锁读取（FR-010）：同会话并发问答的"读-改-写"串行化，防半轮消息。 */
    @Select("SELECT * FROM chat_memory WHERE memory_id = #{memoryId} FOR UPDATE")
    ChatMemoryEntity selectByIdForUpdate(@Param("memoryId") String memoryId);
}
