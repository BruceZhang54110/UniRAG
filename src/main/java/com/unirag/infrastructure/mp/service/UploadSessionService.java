package com.unirag.infrastructure.mp.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.unirag.infrastructure.mp.entity.UploadSession;

import java.util.Date;
import java.util.List;

public interface UploadSessionService extends IService<UploadSession> {

    /**
     * 过期清理扫描：已过 expires_at 的 INIT 会话。
     */
    List<UploadSession> findExpiredInitSessions(Date now);
}
