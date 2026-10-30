/**
 * 多轮对话页测试（016-sse-chat-streaming T14，FR-005/006/010）：
 * - 流式首轮流式追加渲染，done 后展示完整回答与引用；
 * - NO_CONTEXT 独立成态、失败可重试；
 * - 多轮：第二轮自动携带 sessionId、历史按序回显；
 * - 刷新后历史可见；流式中输入禁用可停止；会话内串行约束。
 *
 * 这里 mock 掉 `@/api/retrieval` 的 `askStream`（不篡改全局 fetch），不访问任何后端。
 */

import { fireEvent, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError } from '@/api/client';
import { askStream, NO_CONTEXT_REASON, type AskResult } from '@/api/retrieval';
import { zhCN } from '@/content/zh-CN';
import ChatPage from '@/features/chat/ChatPage';
import { renderWithProviders } from '@/test/render';

vi.mock('@/api/retrieval', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/retrieval')>();
  return { ...actual, askStream: vi.fn() };
});

const askStreamMock = vi.mocked(askStream);

const QUESTION = '向量数据库存在哪里？';
const CHAT_ENTRY = [`/chat?q=${encodeURIComponent(QUESTION)}`];

function buildAnswer(overrides: Partial<AskResult> = {}): AskResult {
  return {
    traceId: 'trace-1',
    answer: '原文通过统一存储抽象写入数据库。[1]',
    citations: [
      {
        documentId: 620,
        docName: 'demo-doc-1',
        pipelineKey: 'main',
        chunkIndex: 3,
        score: 0.42,
        text: '原文文件以键值形式保存在数据库中。',
      },
    ],
    degraded: false,
    degradeReason: null,
    ...overrides,
  };
}

/** 模拟两段增量后完成的流式回答 */
function mockStreamingAnswer(result: AskResult = buildAnswer()) {
  askStreamMock.mockImplementation(async (_params, handlers) => {
    handlers.onDelta('原文通过统一存储抽象');
    handlers.onDelta('写入数据库。[1]');
    return result;
  });
}

beforeEach(() => {
  askStreamMock.mockReset();
  sessionStorage.clear();
  localStorage.clear();
});

describe('ChatPage 多轮对话', () => {
  it('首轮：流式增量追加渲染，done 后展示完整回答与引用', async () => {
    mockStreamingAnswer();

    renderWithProviders(<ChatPage />, CHAT_ENTRY);

    await waitFor(() => expect(screen.getByText(zhCN.chat.answerTitle)).toBeInTheDocument());
    expect(screen.getByText(buildAnswer().answer)).toBeInTheDocument();
    expect(askStreamMock).toHaveBeenCalledWith(
      expect.objectContaining({ question: QUESTION, sessionId: expect.any(String) }),
      expect.anything(),
      expect.anything(),
    );

    const citation = screen.getAllByTestId('chat-citation')[0];
    expect(citation.textContent).toContain('demo-doc-1');
  });

  it('NO_CONTEXT 独立成态，不渲染引用清单', async () => {
    mockStreamingAnswer(
      buildAnswer({
        answer: '知识库中未找到相关内容，无法基于现有资料回答该问题。',
        citations: [],
        degraded: true,
        degradeReason: NO_CONTEXT_REASON,
      }),
    );

    renderWithProviders(<ChatPage />, CHAT_ENTRY);

    await waitFor(() => expect(screen.getByText(zhCN.chat.noContextTitle)).toBeInTheDocument());
    expect(screen.queryByTestId('chat-citations')).not.toBeInTheDocument();
  });

  it('失败可重试，重试重新发起流式请求', async () => {
    askStreamMock.mockRejectedValue(
      new ApiError({ code: 'LLM_UNAVAILABLE', message: zhCN.errors.serviceUnavailable, status: 502 }),
    );

    renderWithProviders(<ChatPage />, CHAT_ENTRY);

    await waitFor(() => expect(screen.getByText(zhCN.errors.bannerTitle)).toBeInTheDocument());

    mockStreamingAnswer();
    fireEvent.click(screen.getByRole('button', { name: zhCN.common.retry }));

    await waitFor(() => expect(screen.getByText(zhCN.chat.answerTitle)).toBeInTheDocument());
    expect(askStreamMock).toHaveBeenCalledTimes(2);
  });

  it('多轮：第二轮自动携带同一会话标识，历史按序回显', async () => {
    mockStreamingAnswer();
    renderWithProviders(<ChatPage />, CHAT_ENTRY);
    await waitFor(() => expect(screen.getByText(buildAnswer().answer)).toBeInTheDocument());

    const SECOND = '那切片的粒度怎么配？';
    mockStreamingAnswer(buildAnswer({ answer: '按管线配置 chunk 上限与重叠。[1]' }));
    fireEvent.change(screen.getByPlaceholderText(zhCN.chat.inputPlaceholder), {
      target: { value: SECOND },
    });
    fireEvent.keyDown(screen.getByPlaceholderText(zhCN.chat.inputPlaceholder), { key: 'Enter' });

    await waitFor(() => expect(screen.getByText('按管线配置 chunk 上限与重叠。[1]')).toBeInTheDocument());
    // 两轮历史都在页面上
    expect(screen.getAllByTestId('chat-message')).toHaveLength(2);
    expect(screen.getByText(SECOND)).toBeInTheDocument();

    const calls = askStreamMock.mock.calls;
    expect(calls[1][0].sessionId).toEqual(calls[0][0].sessionId);
  });

  it('刷新后同会话历史回显，且不带 ?q= 不会自动发问', async () => {
    mockStreamingAnswer();
    const { unmount } = renderWithProviders(<ChatPage />, CHAT_ENTRY);
    await waitFor(() => expect(screen.getByText(buildAnswer().answer)).toBeInTheDocument());
    const callsAfterFirst = askStreamMock.mock.calls.length;
    unmount();

    // 模拟刷新：重新挂载（sessionStorage 的 sessionId 保留）
    renderWithProviders(<ChatPage />, ['/chat']);
    expect(screen.getByText(buildAnswer().answer)).toBeInTheDocument();
    expect(askStreamMock).toHaveBeenCalledTimes(callsAfterFirst);
  });

  it('流式中输入禁用并显示停止，停止后该轮标记失败可重试', async () => {
    askStreamMock.mockImplementation(
      (_params, handlers, signal) =>
        new Promise<AskResult>((_resolve, reject) => {
          handlers.onDelta('半段回答');
          signal?.addEventListener('abort', () =>
            reject(new DOMException('Aborted', 'AbortError')),
          );
        }),
    );

    renderWithProviders(<ChatPage />, CHAT_ENTRY);

    await waitFor(() => expect(screen.getByText(zhCN.chat.stopAnswer)).toBeInTheDocument());
    expect(screen.getByPlaceholderText('')).toBeDisabled();

    fireEvent.click(screen.getByRole('button', { name: zhCN.chat.stopAnswer }));

    await waitFor(() => expect(screen.getByText(zhCN.chat.stoppedHint)).toBeInTheDocument());
    expect(screen.getByRole('button', { name: zhCN.common.retry })).toBeInTheDocument();
  });

  it('新对话按钮清空历史并换新会话', async () => {
    mockStreamingAnswer();
    renderWithProviders(<ChatPage />, CHAT_ENTRY);
    await waitFor(() => expect(screen.getByText(buildAnswer().answer)).toBeInTheDocument());

    fireEvent.click(screen.getByRole('button', { name: zhCN.chat.newConversation }));

    expect(screen.queryByTestId('chat-message')).not.toBeInTheDocument();
  });
});
