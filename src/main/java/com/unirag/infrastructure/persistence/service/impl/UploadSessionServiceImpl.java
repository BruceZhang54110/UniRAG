package com.unirag.infrastructure.persistence.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.unirag.infrastructure.persistence.entity.UploadSession;
import com.unirag.infrastructure.persistence.mapper.UploadSessionMapper;
import com.unirag.infrastructure.persistence.service.UploadSessionService;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

@Service
public class UploadSessionServiceImpl
        extends ServiceImpl<UploadSessionMapper, UploadSession>
        implements UploadSessionService {

    @Override
    public List<UploadSession> findExpiredInitSessions(Date now) {
        return baseMapper.findExpiredInitSessions(now);
    }
}
