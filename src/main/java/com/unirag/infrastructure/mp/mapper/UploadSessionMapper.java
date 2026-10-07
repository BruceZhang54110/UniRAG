package com.unirag.infrastructure.mp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.unirag.infrastructure.mp.entity.UploadSession;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

/**
 * @Entity com.unirag.infrastructure.mp.entity.UploadSession
 */
public interface UploadSessionMapper extends BaseMapper<UploadSession> {

    /**
     * 过期清理扫描：已过 expires_at 的 INIT 会话。
     */
    List<UploadSession> findExpiredInitSessions(@Param("now") Date now);
}
