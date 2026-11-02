import { describe, expect, it } from 'vitest';

import type { ChatHistoryMessage } from '@/api/chatHistory';
import { NO_CONTEXT_REASON } from '@/api/retrieval';
import { zhCN } from '@/content/zh-CN';
import {
  HISTORY_PAGE_SIZE,
  lastPageOf,
  mergeEarlierPages,
  toMessages,
} from '@/features/chat/history';

function message(overrides: Partial<ChatHistoryMessage> = {}): ChatHistoryMessage {
  return {
    roundSeq: 1,
    role: 'USER',
    content: '问题',
    status: 'SUCCESS',
    degradeReason: null,
    traceId: 't',
    createdAt: null,
    ...overrides,
  };
}

describe('历史分页计算', () => {
  it('total 为空或非法时为第 0 页', () => {
    expect(lastPageOf(0)).toBe(0);
    expect(lastPageOf(Number.NaN)).toBe(0);
    expect(lastPageOf(-1)).toBe(0);
  });

  it('不足一页与刚好整页都不需要额外请求', () => {
    expect(lastPageOf(1)).toBe(0);
    expect(lastPageOf(HISTORY_PAGE_SIZE)).toBe(0);
  });

  it('超过一页时指向最后一页', () => {
    expect(lastPageOf(HISTORY_PAGE_SIZE + 1)).toBe(1);
    expect(lastPageOf(250)).toBe(2);
    expect(lastPageOf(200)).toBe(1);
  });

  it('自定义页大小生效', () => {
    expect(lastPageOf(10, 5)).toBe(1);
    expect(lastPageOf(10, 0)).toBe(0);
  });
});

describe('更早历史合并', () => {
  it('按 roundSeq 升序，同轮 USER 在 AI 之前', () => {
    const merged = mergeEarlierPages(
      [message({ roundSeq: 2, role: 'AI', content: '答2' })],
      [message({ roundSeq: 1 }), message({ roundSeq: 2 })],
    );

    expect(merged.map((item) => `${item.roundSeq}-${item.role}`)).toEqual([
      '1-USER',
      '2-USER',
      '2-AI',
    ]);
  });

  it('重复请求不会产生重复消息', () => {
    const page = [message({ roundSeq: 1 }), message({ roundSeq: 1, role: 'AI' })];
    expect(mergeEarlierPages(page, page)).toHaveLength(2);
  });
});

describe('历史状态保真', () => {
  it('成功轮次恢复为完成态', () => {
    const [restored] = toMessages([
      message({ roundSeq: 1 }),
      message({ roundSeq: 1, role: 'AI', content: '回答' }),
    ]);

    expect(restored).toMatchObject({ status: 'done', degraded: false, answer: '回答' });
  });

  it('检索为空恢复为降级态，而不是普通回答', () => {
    const [restored] = toMessages([
      message({ roundSeq: 1 }),
      message({ roundSeq: 1, role: 'AI', content: '未找到', degradeReason: NO_CONTEXT_REASON }),
    ]);

    expect(restored.degraded).toBe(true);
    expect(restored.degradeReason).toBe(NO_CONTEXT_REASON);
  });

  it('中止轮次恢复为失败态并保留已停止文案', () => {
    const [restored] = toMessages([
      message({ roundSeq: 1 }),
      message({ roundSeq: 1, role: 'AI', content: '半截', status: 'ABORTED' }),
    ]);

    expect(restored.status).toBe('error');
    expect(restored.errorMessage).toBe(zhCN.chat.stoppedHint);
    expect(restored.answer).toBe('半截');
  });

  it('失败轮次与缺少回答行的轮次都恢复为可重试失败态', () => {
    const failed = toMessages([
      message({ roundSeq: 1 }),
      message({ roundSeq: 1, role: 'AI', content: '', status: 'FAILED' }),
    ])[0];
    const incomplete = toMessages([message({ roundSeq: 2 })])[0];

    expect(failed.status).toBe('error');
    expect(failed.errorMessage).toBe(zhCN.errors.serviceUnavailable);
    expect(incomplete.status).toBe('error');
  });

  it('历史回显不带引用，也不伪造引用', () => {
    const [restored] = toMessages([
      message({ roundSeq: 1 }),
      message({ roundSeq: 1, role: 'AI', content: '回答 [1]' }),
    ]);

    expect(restored.citations).toEqual([]);
  });
});
