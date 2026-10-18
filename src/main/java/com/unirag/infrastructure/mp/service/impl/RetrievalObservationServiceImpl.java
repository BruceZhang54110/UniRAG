package com.unirag.infrastructure.mp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.unirag.infrastructure.mp.entity.RetrievalObservation;
import com.unirag.infrastructure.mp.mapper.RetrievalObservationMapper;
import com.unirag.infrastructure.mp.service.RetrievalObservationService;
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
