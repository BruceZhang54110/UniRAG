/**
 * 检索问答接口层（contracts/retrieval-api.md §3）。
 *
 * 复用 `api/client.ts` 的 `{data}` 解包与错误归一、`errors.ts` 的错误码文案，
 * 不新增 HTTP 客户端抽象；`X-Tenant-Code` 由 `client.ts` 统一注入。
 */

import { apiFetch, apiRequest, ensureOk } from './client';
import { SseParser } from './sse-parse';

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
  /** 取值范围 `[0,1]`，语义为 `(1 + cosine_similarity) / 2`；省略时后端按校准默认 0.70 过滤 */
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

const ASK_STREAM_PATH = '/v1/ask/stream';

export interface AskStreamHandlers {
  /** 内容增量：仅用于就地渲染；全量结果以返回值（done 事件）为准 */
  onDelta: (delta: string) => void;
}

/**
 * 流式问答（016-sse-chat-streaming，contracts/ask-stream-endpoint.md）：fetch 流式读取
 * SSE 事件，`message` 增量回调、`done` 时 resolve 全量结果（与非流式 `AskResult` 同形状）、
 * `error` 事件或非 2xx 时抛归一 `ApiError`；`signal` 中止透传给后端（断开的轮次不入记忆）。
 */
export async function askStream(
  params: AskParams,
  handlers: AskStreamHandlers,
  signal?: AbortSignal,
): Promise<AskResult> {
  const response = await apiFetch(ASK_STREAM_PATH, {
    method: 'POST',
    body: JSON.stringify({
      question: params.question,
      granularity: params.granularity,
      topK: params.topK,
      minScore: params.minScore,
      sessionId: params.sessionId,
    }),
    signal,
  });
  await ensureOk(response);

  const parser = new SseParser();
  const reader = response.body?.getReader();
  if (!reader) {
    throw new Error('当前浏览器不支持流式读取');
  }
  const decoder = new TextDecoder();
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    for (const event of parser.feed(decoder.decode(value, { stream: true }))) {
      if (event.event === 'message') {
        handlers.onDelta(toText(asRecord(JSON.parse(event.data)).delta));
      } else if (event.event === 'done') {
        return normalizeAskResult(JSON.parse(event.data));
      } else if (event.event === 'error') {
        const payload = asRecord(JSON.parse(event.data));
        const { ApiError } = await import('./client');
        throw new ApiError({
          code: toText(payload.code) || 'STREAM_ERROR',
          message: toText(payload.message) || '问答处理失败',
          status: 0,
        });
      }
    }
  }
  const { ApiError } = await import('./client');
  throw new ApiError({ code: 'STREAM_INCOMPLETE', message: '回答流意外结束', status: 0 });
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
