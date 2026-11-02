/**
 * 会话消息历史接口层（017-chat-history，contracts/chat-history-endpoint.md）：
 * 完整对话历史回放的唯一数据源（替代 016 前的 localStorage 本地副本）。
 * 复用 `api/client.ts` 的错误归一；X-Tenant-Code 由 client.ts 统一注入。
 */

import { apiRequest } from './client';

export interface ChatHistoryMessage {
  roundSeq: number;
  role: 'USER' | 'AI';
  content: string;
  status: 'SUCCESS' | 'ABORTED' | 'FAILED';
  degradeReason: string | null;
  traceId: string | null;
  createdAt: number | null;
}

export interface ChatHistoryPage {
  sessionId: string;
  total: number;
  page: number;
  size: number;
  items: ChatHistoryMessage[];
}

function asRecord(value: unknown): Record<string, unknown> {
  return typeof value === 'object' && value !== null ? (value as Record<string, unknown>) : {};
}

function toText(value: unknown): string {
  return typeof value === 'string' ? value : '';
}

function toNumber(value: unknown): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : 0;
}

function normalizeMessage(raw: unknown): ChatHistoryMessage {
  const item = asRecord(raw);
  return {
    roundSeq: toNumber(item.roundSeq),
    role: item.role === 'AI' ? 'AI' : 'USER',
    content: toText(item.content),
    status: (['SUCCESS', 'ABORTED', 'FAILED'].includes(toText(item.status))
      ? toText(item.status)
      : 'SUCCESS') as ChatHistoryMessage['status'],
    degradeReason: typeof item.degradeReason === 'string' ? item.degradeReason : null,
    traceId: typeof item.traceId === 'string' ? item.traceId : null,
    createdAt: typeof item.createdAt === 'number' ? item.createdAt : null,
  };
}

/** `GET /api/v1/chat-memories/{sessionId}/messages`：正序分页回放完整对话历史。 */
export async function getChatHistory(sessionId: string, page = 0, size = 100): Promise<ChatHistoryPage> {
  const raw = await apiRequest<unknown>(
    `/v1/chat-memories/${encodeURIComponent(sessionId)}/messages?page=${page}&size=${size}`,
  );
  const data = asRecord(raw);
  const items = Array.isArray(data.items) ? data.items : [];
  return {
    sessionId: toText(data.sessionId) || sessionId,
    total: toNumber(data.total),
    page: toNumber(data.page),
    size: toNumber(data.size),
    items: items.map(normalizeMessage),
  };
}

/** `DELETE /api/v1/chat-memories/{sessionId}`：清空会话（记忆 + 完整历史级联删除，幂等）。 */
export async function clearChatSession(sessionId: string): Promise<void> {
  await apiRequest<unknown>(`/v1/chat-memories/${encodeURIComponent(sessionId)}`, {
    method: 'DELETE',
  });
}
