package com.unirag.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.unirag.infrastructure.persistence.entity.TenantResourceConfig;

import java.util.List;

/**
* @author zhw
* @description 针对表【tenant_resource_config】的数据库操作Mapper
* @createDate 2026-09-16 14:44:06
* @Entity com.unirag.infrastructure.persistence.entity.TenantResourceConfig
*/
public interface TenantResourceConfigMapper extends BaseMapper<TenantResourceConfig> {

    /**
     * 查询所有启用租户下的启用资源配置（联表带出 tenant_code，按配置 id 升序，
     * 保证重复 primary 防御时"取 id 最小者"的确定性，research.md R6）。
     */
    List<TenantResourceConfig> findAllEnabled();
}




