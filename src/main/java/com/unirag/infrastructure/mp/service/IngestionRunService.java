package com.unirag.infrastructure.mp.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.unirag.infrastructure.mp.entity.IngestionRun;

import java.util.List;

public interface IngestionRunService extends IService<IngestionRun> {

    /**
     * 查询某文档的全部管线执行记录（按 id 升序）。
     */
    List<IngestionRun> findByDocumentId(Long documentId);

    /**
     * 查询给定文档中未完结（finished_at 为空）的执行记录（启动恢复任务用，research.md R6）。
     */
    List<IngestionRun> findUnfinishedByDocumentIds(List<Long> documentIds);
}
