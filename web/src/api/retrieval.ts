/**
 * 检索问答接口层（contracts/retrieval-api.md §3）。
 *
 * 复用 `api/client.ts` 的 `{data}` 解包与错误归一、`errors.ts` 的错误码文案，
 * 不新增 HTTP 客户端抽象；`X-Tenant-Code` 由 `client.ts` 统一注入。
 */

import { apiRequest } from './client';

export interface Citation {
  documentId: number;
  docName: string;
  pipelineKey: string;
  chunkIndex: number;
  score: number;
  text: string;
}

export interface AskResult {
  traceId: string;
  answer: string;
  citations: Citation[];
  degraded: boolean;
  degradeReason: string | null;
}

/** 检索为空时的降级原因（contracts §3） */
export const NO_CONTEXT_REASON = 'NO_CONTEXT';

const ASK_PATH = '/v1/ask';

export interface AskParams {
  question: string;
  /** 摄取管线 key 或向量存储资源 key；省略表示检索全部已启用管线 */
  granularity?: string;
  topK?: number;
  /** 取值范围 `[0,1]`，语义为 `(1 + cosine_similarity) / 2` */
  minScore?: number;
  sessionId?: string;
  signal?: AbortSignal;
}

/** `POST /api/v1/ask` —— 检索片段 → 生成回答 → 返回回答与引用清单 */
export async function ask(params: AskParams): Promise<AskResult> {
  const data = await apiRequest<unknown>(ASK_PATH, {
    method: 'POST',
    // undefined 字段被 JSON.stringify 自动省略，交由后端取默认值
    body: JSON.stringify({
      question: params.question,
      granularity: params.granularity,
      topK: params.topK,
      minScore: params.minScore,
      sessionId: params.sessionId,
    }),
    signal: params.signal,
  });
  return normalizeAskResult(data);
}

function asRecord(value: unknown): Record<string, unknown> {
  return typeof value === 'object' && value !== null ? (value as Record<string, unknown>) : {};
}

function toText(value: unknown): string {
  return typeof value === 'string' ? value : '';
}

function toNumber(value: unknown): number {
  if (typeof value === 'number' && Number.isFinite(value)) return value;
  const parsed = typeof value === 'string' && value.trim() !== '' ? Number(value) : Number.NaN;
  return Number.isFinite(parsed) ? parsed : 0;
}

function normalizeCitation(raw: unknown): Citation {
  const item = asRecord(raw);
  return {
    documentId: toNumber(item.documentId),
    docName: toText(item.docName),
    pipelineKey: toText(item.pipelineKey),
    chunkIndex: toNumber(item.chunkIndex),
    score: toNumber(item.score),
    text: toText(item.text),
  };
}

function normalizeAskResult(raw: unknown): AskResult {
  const payload = asRecord(raw);
  const citations = Array.isArray(payload.citations) ? payload.citations : [];
  return {
    traceId: toText(payload.traceId),
    answer: toText(payload.answer),
    citations: citations.map(normalizeCitation),
    degraded: payload.degraded === true,
    degradeReason: typeof payload.degradeReason === 'string' ? payload.degradeReason : null,
  };
}
