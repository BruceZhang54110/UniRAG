/**
 * 历史回显与分页（018-chat-page-layout，FR-025~028 / FR-044~048）。
 *
 * 两个纯职责：
 * 1. **状态保真**：把历史里的 `SUCCESS / ABORTED / FAILED / degradeReason` 还原成页面状态，
 *    避免刷新后失败与"检索为空"被洗白成普通回答；
 * 2. **分页窗口**：服务端按 id 正序分页（`page=0` 是最早一页），先算最后一页才能看到最新内容。
 *
 * 历史不保存引用清单，因此回显的消息 `citations` 恒为空（FR-028，不伪造）。
 */

import type { ChatHistoryMessage } from '@/api/chatHistory';
import { NO_CONTEXT_REASON } from '@/api/retrieval';
import { zhCN } from '@/content/zh-CN';

import type { ChatMessage } from './types';

/** 单页条数：接口上限 200，取 100 兼顾载荷与请求次数 */
export const HISTORY_PAGE_SIZE = 100;

/** 最后一页页码（从 0 起）；`total<=0` 时为 0 */
export function lastPageOf(total: number, pageSize: number = HISTORY_PAGE_SIZE): number {
  if (!Number.isFinite(total) || total <= 0 || !Number.isFinite(pageSize) || pageSize <= 0) return 0;
  return Math.max(0, Math.ceil(total / pageSize) - 1);
}

/** 是否还存在更早的页 */
export function hasEarlier(earliestPage: number): boolean {
  return earliestPage > 0;
}

function messageKey(message: ChatHistoryMessage): string {
  return `${message.roundSeq}-${message.role}`;
}

/** 前置合并更早一页：按 `roundSeq + role` 去重，整体按轮次升序 */
export function mergeEarlierPages(
  current: ChatHistoryMessage[],
  incoming: ChatHistoryMessage[],
): ChatHistoryMessage[] {
  const byKey = new Map<string, ChatHistoryMessage>();
  for (const message of incoming) byKey.set(messageKey(message), message);
  for (const message of current) byKey.set(messageKey(message), message);

  return [...byKey.values()].sort((left, right) => {
    if (left.roundSeq !== right.roundSeq) return left.roundSeq - right.roundSeq;
    return left.role === right.role ? 0 : left.role === 'USER' ? -1 : 1;
  });
}

interface RoundDraft {
  question: string;
  answer: string;
  status: ChatHistoryMessage['status'] | null;
  degradeReason: string | null;
}

/** 历史消息 → 页面消息：同轮问答合并为一条，状态按 data-model §1.4 映射 */
export function toMessages(items: ChatHistoryMessage[]): ChatMessage[] {
  const rounds = new Map<number, RoundDraft>();

  for (const item of items) {
    const draft = rounds.get(item.roundSeq) ?? {
      question: '',
      answer: '',
      status: null,
      degradeReason: null,
    };

    if (item.role === 'USER') {
      draft.question = item.content;
    } else {
      draft.answer = item.content;
      draft.status = item.status;
      draft.degradeReason = item.degradeReason;
    }

    rounds.set(item.roundSeq, draft);
  }

  return [...rounds.entries()]
    .sort(([left], [right]) => left - right)
    .map(([roundSeq, draft]) => restoreMessage(roundSeq, draft));
}

function restoreMessage(roundSeq: number, draft: RoundDraft): ChatMessage {
  const base: ChatMessage = {
    id: `history-${roundSeq}`,
    question: draft.question,
    answer: draft.answer,
    citations: [],
    degraded: false,
    degradeReason: null,
    status: 'done',
  };

  if (draft.status === 'ABORTED') {
    return { ...base, status: 'error', errorMessage: zhCN.chat.stoppedHint };
  }

  if (draft.status === 'FAILED' || draft.status === null) {
    // 只有 USER 行说明该轮未完成：按失败呈现并可重试，而不是伪造回答
    return { ...base, status: 'error', errorMessage: zhCN.errors.serviceUnavailable };
  }

  if (draft.degradeReason === NO_CONTEXT_REASON) {
    return { ...base, degraded: true, degradeReason: NO_CONTEXT_REASON };
  }

  return base;
}
