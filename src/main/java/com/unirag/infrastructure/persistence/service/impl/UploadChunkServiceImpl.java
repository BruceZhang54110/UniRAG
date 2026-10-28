package com.unirag.infrastructure.persistence.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.unirag.infrastructure.persistence.entity.UploadChunk;
import com.unirag.infrastructure.persistence.mapper.UploadChunkMapper;
import com.unirag.infrastructure.persistence.service.UploadChunkService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class UploadChunkServiceImpl
        extends ServiceImpl<UploadChunkMapper, UploadChunk>
        implements UploadChunkService {

    @Override
    public List<Integer> findSeqsBySessionId(Long sessionId) {
        return baseMapper.findSeqsBySessionId(sessionId);
    }

    @Override
    public List<UploadChunk> findBySessionIdOrderBySeq(Long sessionId) {
        return baseMapper.findBySessionIdOrderBySeq(sessionId);
    }

    @Override
    public int deleteBySessionId(Long sessionId) {
        return baseMapper.deleteBySessionId(sessionId);
    }
}
