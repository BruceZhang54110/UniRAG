package com.unirag.infrastructure.persistence.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.unirag.infrastructure.persistence.entity.UploadChunk;

import java.util.List;

public interface UploadChunkService extends IService<UploadChunk> {

    /**
     * 已收块清单（断点续传客户端只补缺块的依据）。
     */
    List<Integer> findSeqsBySessionId(Long sessionId);

    /**
     * 按 seq 升序读全部块（完成拼装用）。
     */
    List<UploadChunk> findBySessionIdOrderBySeq(Long sessionId);

    /**
     * 删除会话全部暂存块。
     */
    int deleteBySessionId(Long sessionId);
}
