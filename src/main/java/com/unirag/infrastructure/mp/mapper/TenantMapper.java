package com.unirag.infrastructure.mp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.unirag.infrastructure.mp.entity.Tenant;
import org.apache.ibatis.annotations.Param;

/**
* @author zhw
* @description 针对表【tenant】的数据库操作Mapper
* @createDate 2026-09-16 14:44:06
* @Entity com.unirag.infrastructure.mp.entity.Tenant
*/
public interface TenantMapper extends BaseMapper<Tenant> {

    /**
     * 按租户码查询未软删除租户（ingestion 等按 tenant_code 定位租户，research.md R5）。
     */
    Tenant findByCode(@Param("tenantCode") String tenantCode);
}



