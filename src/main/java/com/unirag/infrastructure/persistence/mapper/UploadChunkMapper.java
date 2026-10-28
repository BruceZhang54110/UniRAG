package com.unirag.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.unirag.infrastructure.persistence.entity.UploadChunk;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * @Entity com.unirag.infrastructure.persistence.entity.UploadChunk
 */
public interface UploadChunkMapper extends BaseMapper<UploadChunk> {

    /**
     * 已收块清单（断点续传客户端只补缺块的依据）。
     */
    List<Integer> findSeqsBySessionId(@Param("sessionId") Long sessionId);

    /**
     * 按 seq 升序读全部块（完成拼装用）。
     */
    List<UploadChunk> findBySessionIdOrderBySeq(@Param("sessionId") Long sessionId);

    /**
     * 删除会话全部暂存块。
     */
    int deleteBySessionId(@Param("sessionId") Long sessionId);
}
