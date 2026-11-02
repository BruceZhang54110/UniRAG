/**
 * chatHistory 接口层测试（017 T13）：字段归一、空列表、错误归一。
 */

import { ApiError } from '@/api/client';
import { getChatHistory } from '@/api/chatHistory';
import { apiRequest } from '@/api/client';
import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('@/api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/client')>();
  return { ...actual, apiRequest: vi.fn() };
});

const apiRequestMock = vi.mocked(apiRequest);

beforeEach(() => {
  apiRequestMock.mockReset();
});

describe('getChatHistory', () => {
  it('归一化消息字段并透传分页信息', async () => {
    apiRequestMock.mockResolvedValue({
      sessionId: 's-1',
      total: 2,
      page: 0,
      size: 100,
      items: [
        { roundSeq: 1, role: 'USER', content: '问题', status: 'SUCCESS', degradeReason: null, traceId: 't', createdAt: 1720000000000 },
        { roundSeq: 1, role: 'AI', content: '回答', status: 'BOGUS', degradeReason: 'NO_CONTEXT', traceId: null, createdAt: null },
      ],
    });

    const page = await getChatHistory('s-1');

    expect(page.total).toBe(2);
    expect(page.items[0]).toEqual({
      roundSeq: 1,
      role: 'USER',
      content: '问题',
      status: 'SUCCESS',
      degradeReason: null,
      traceId: 't',
      createdAt: 1720000000000,
    });
    // 非法 status 归一为 SUCCESS；未知 role 归一为 USER
    expect(page.items[1].status).toBe('SUCCESS');
    expect(page.items[1].degradeReason).toBe('NO_CONTEXT');
  });

  it('空 items 与缺省字段容错', async () => {
    apiRequestMock.mockResolvedValue({ sessionId: 's-2' });

    const page = await getChatHistory('s-2');

    expect(page.items).toEqual([]);
    expect(page.total).toBe(0);
    expect(apiRequestMock).toHaveBeenCalledWith('/v1/chat-memories/s-2/messages?page=0&size=100');
  });

  it('错误按 ApiError 透传', async () => {
    apiRequestMock.mockRejectedValue(new ApiError({ code: 'SESSION_NOT_FOUND', message: '会话不存在', status: 404 }));

    await expect(getChatHistory('nope')).rejects.toMatchObject({ code: 'SESSION_NOT_FOUND' });
  });
});
