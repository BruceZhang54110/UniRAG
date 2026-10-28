package com.unirag.infrastructure.persistence.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.unirag.infrastructure.persistence.entity.RetrievalObservation;

import java.util.Date;

public interface RetrievalObservationService extends IService<RetrievalObservation> {

    /**
     * 物理删除 created_at 早于 {@code cutoff} 的观测记录，返回删除行数（留存清理用，T040）。
     */
    int deleteCreatedBefore(Date cutoff);
}
