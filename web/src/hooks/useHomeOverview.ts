/**
 * 首页概览数据 Hook（T018）：唯一的只读请求 + 状态推导（plan D9，不引入查询库）。
 *
 * 暴露 `{ state, overview, items, reload }`；`errorMessage` 为已归一的中文错误文案，
 * 供告警条展示（interface 扩充项，见交付说明）。
 */

import { useCallback, useEffect, useMemo, useState } from 'react';

import { isAbortError, toApiErrorShape } from '@/api/client';
import { listDocuments, type DocumentSummary } from '@/api/documents';
import {
  deriveOverview,
  deriveViewState,
  emptyOverview,
  type DataState,
  type KnowledgeOverview,
  type ViewState,
} from '@/features/home/state';

/** 首页固定分页大小（contracts/backend-api-consumption.md §1） */
export const HOME_DOCUMENT_PAGE_SIZE = 6;

export interface HomeOverviewResult {
  state: ViewState;
  overview: KnowledgeOverview;
  items: DocumentSummary[];
  /** 已归一的错误文案；非错误态为 `null` */
  errorMessage: string | null;
  reload: () => void;
}

export function useHomeOverview(): HomeOverviewResult {
  const [dataState, setDataState] = useState<DataState>('loading');
  const [items, setItems] = useState<DocumentSummary[]>([]);
  const [total, setTotal] = useState(0);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [reloadToken, setReloadToken] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    setDataState('loading');
    setErrorMessage(null);

    listDocuments({ page: 1, size: HOME_DOCUMENT_PAGE_SIZE, signal: controller.signal })
      .then((result) => {
        setItems(result.items);
        setTotal(result.total);
        setDataState('ready');
      })
      .catch((error: unknown) => {
        if (isAbortError(error)) return;
        setItems([]);
        setTotal(0);
        setErrorMessage(toApiErrorShape(error).message);
        setDataState('error');
      });

    return () => controller.abort();
  }, [reloadToken]);

  const overview = useMemo(
    () => (dataState === 'ready' ? deriveOverview(items, total) : emptyOverview()),
    [dataState, items, total],
  );

  const state = deriveViewState(dataState, overview);
  const reload = useCallback(() => setReloadToken((token) => token + 1), []);

  return { state, overview, items, errorMessage, reload };
}
