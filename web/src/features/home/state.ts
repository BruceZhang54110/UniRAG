/**
 * 首页知识库视图状态推导与建议问题生成（纯函数，data-model §1.3 / §1.4 / §3）。
 *
 * 状态合成：数据维度（`loading | error | ready`）× 内容维度（`empty | processing | ready`），
 * 优先级 **error > empty > processing > ready**（失败不得被渲染为"知识库为空"）。
 */

import { zhCN } from '@/content/zh-CN';
import type { DocumentSummary } from '@/api/documents';

export type DataState = 'loading' | 'error' | 'ready';
export type ViewState = 'loading' | 'error' | 'empty' | 'processing' | 'ready';

export interface KnowledgeOverview {
  total: number;
  /** 已加载 items 中可检索（INGESTED）的数量；受分页窗口限制，仅作提示用 */
  retrievable: number;
  /** 已加载 items 中处理中（INGESTING）的数量 */
  processing: number;
  hasDocuments: boolean;
}

export interface Suggestion {
  id: string;
  text: string;
  source: 'document' | 'preset';
  enabled: boolean;
}

/** 建议问题总条数（FR-007 要求 3~4 条，取 4） */
export const SUGGESTION_COUNT = 4;

/** 空内容判断：去空白后为空则不提交（FR-004） */
export function isEmptyQuestion(value: string): boolean {
  return value.trim().length === 0;
}

/** 展示标题：`title` 非空且非空白时用它，否则回退 `docName`（data-model §1.1） */
export function displayTitle(item: Pick<DocumentSummary, 'title' | 'docName'>): string {
  const title = item.title?.trim();
  return title ? title : item.docName;
}

/** 文件类型推断：优先 `mimeType`，其次扩展名，均不可判定时为 `file`（data-model §1.1） */
export function deriveFiletype(item: Pick<DocumentSummary, 'mimeType' | 'docName'>): string {
  const mime = item.mimeType?.toLowerCase() ?? '';
  if (mime.includes('markdown')) return 'md';
  if (mime.includes('text/plain')) return 'txt';

  const extension = item.docName.split('.').pop()?.toLowerCase() ?? '';
  if (extension === 'md' || extension === 'markdown') return 'md';
  if (extension === 'txt') return 'txt';
  return 'file';
}

/** 由列表响应派生概览（不新增接口，data-model §1.3） */
export function deriveOverview(items: DocumentSummary[], total: number): KnowledgeOverview {
  const safeTotal = Number.isFinite(total) && total > 0 ? total : 0;
  return {
    total: safeTotal,
    retrievable: items.filter((item) => item.status === 'INGESTED').length,
    processing: items.filter((item) => item.status === 'INGESTING').length,
    hasDocuments: safeTotal > 0,
  };
}

export function emptyOverview(): KnowledgeOverview {
  return { total: 0, retrievable: 0, processing: 0, hasDocuments: false };
}

/**
 * 合成视图状态。`loading` 优先于内容维度（数据未回来时不预判空库）；
 * `error` 优先级最高，`empty` 次之。
 */
export function deriveViewState(dataState: DataState, overview: KnowledgeOverview): ViewState {
  if (dataState === 'error') return 'error';
  if (dataState === 'loading') return 'loading';
  if (!overview.hasDocuments) return 'empty';
  if (overview.retrievable === 0) return 'processing';
  return 'ready';
}

/**
 * 提问框可用性：仅 `empty` 与 `error` 禁用（ui-contract §2.2）。
 * `loading` 不禁用：数据未返回不阻塞提问（data-model §3）。
 */
export function canAsk(viewState: ViewState): boolean {
  return viewState !== 'empty' && viewState !== 'error';
}

/** 提问框禁用原因文案（禁用时不得静默） */
export function askDisabledReason(viewState: ViewState): string | null {
  if (viewState === 'empty') return zhCN.ask.disabledEmpty;
  if (viewState === 'error') return zhCN.ask.disabledError;
  return null;
}

/** 建议问题可用性：`hasDocuments && retrievable > 0`（data-model §1.4） */
export function suggestionsEnabled(overview: KnowledgeOverview): boolean {
  return overview.hasDocuments && overview.retrievable > 0;
}

/**
 * 生成建议问题（data-model §1.4）：
 * 1. 前 2 条可检索文档各生成一条"《{title}》讲了什么？"；
 * 2. 用预置问题补齐到 4 条（文档不足 2 条时预置更多，保证总数恒为 4）。
 */
export function buildSuggestions(items: DocumentSummary[], overview: KnowledgeOverview): Suggestion[] {
  const enabled = suggestionsEnabled(overview);

  const documentSuggestions: Suggestion[] = items
    .filter((item) => item.status === 'INGESTED')
    .slice(0, 2)
    .map((item, index) => ({
      id: `document-${item.docName}-${index}`,
      text: zhCN.suggestions.fromDocument.replace('{title}', displayTitle(item)),
      source: 'document' as const,
      enabled,
    }));

  const missing = Math.max(0, SUGGESTION_COUNT - documentSuggestions.length);
  const presetSuggestions: Suggestion[] = zhCN.suggestions.presets
    .slice(0, missing)
    .map((text, index) => ({
      id: `preset-${index}`,
      text,
      source: 'preset' as const,
      enabled,
    }));

  return [...documentSuggestions, ...presetSuggestions];
}
