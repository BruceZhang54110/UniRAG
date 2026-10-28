package com.unirag.infrastructure.persistence.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.unirag.infrastructure.persistence.entity.RetrievalObservation;
import com.unirag.infrastructure.persistence.mapper.RetrievalObservationMapper;
import com.unirag.infrastructure.persistence.service.RetrievalObservationService;
import org.springframework.stereotype.Service;

import java.util.Date;

@Service
public class RetrievalObservationServiceImpl
        extends ServiceImpl<RetrievalObservationMapper, RetrievalObservation>
        implements RetrievalObservationService {

    @Override
    public int deleteCreatedBefore(Date cutoff) {
        return baseMapper.delete(new LambdaQueryWrapper<RetrievalObservation>()
                .lt(RetrievalObservation::getCreatedAt, cutoff));
    }
}
