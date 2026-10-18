/**
 * T026 / US2 测试：问答页三态（FR-013、ui-contract §2.4）。
 *
 * 覆盖：
 * - 加载态：问题已渲染，回答区为加载提示且不出现引用清单；
 * - 空结果态：`degradeReason === 'NO_CONTEXT'` 时给出明确的"未找到相关内容"语义，
 *   而不是把降级回答伪装成正常回答，也不渲染引用清单；
 * - 失败态：给出已归一的失败文案与重试入口，且**不得**呈现为"知识库为空"。
 *
 * 实现事实：这里 mock 掉 `@/api/retrieval` 的 `ask`（不篡改全局 fetch），
 * 因此测试不访问任何后端服务。
 */

import { fireEvent, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError } from '@/api/client';
import { ask, NO_CONTEXT_REASON, type AskResult } from '@/api/retrieval';
import { zhCN } from '@/content/zh-CN';
import ChatPage from '@/features/chat/ChatPage';
import { renderWithProviders } from '@/test/render';

vi.mock('@/api/retrieval', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/retrieval')>();
  return { ...actual, ask: vi.fn() };
});

const askMock = vi.mocked(ask);

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

beforeEach(() => {
  askMock.mockReset();
});

describe('ChatPage 三态', () => {
  it('加载态：展示问题与加载提示，且不渲染引用清单', () => {
    askMock.mockReturnValue(new Promise<AskResult>(() => {}));

    renderWithProviders(<ChatPage />, CHAT_ENTRY);

    expect(screen.getByText(QUESTION)).toBeInTheDocument();
    expect(screen.getByTestId('chat-answer-loading')).toBeInTheDocument();
    expect(screen.queryByTestId('chat-citations')).not.toBeInTheDocument();
    expect(askMock).toHaveBeenCalledWith(expect.objectContaining({ question: QUESTION }));
  });

  it('正常态：展示回答与引用片段的文档标识与片段序号', async () => {
    askMock.mockResolvedValue(buildAnswer());

    renderWithProviders(<ChatPage />, CHAT_ENTRY);

    await waitFor(() => expect(screen.getByText(zhCN.chat.answerTitle)).toBeInTheDocument());
    expect(screen.getByText(buildAnswer().answer)).toBeInTheDocument();

    const citation = screen.getAllByTestId('chat-citation')[0];
    expect(citation.textContent).toContain('demo-doc-1');
    expect(citation.textContent).toContain('第 3 段');
    expect(citation.textContent).toContain('原文文件以键值形式保存在数据库中。');
    expect(screen.queryByTestId('chat-answer-loading')).not.toBeInTheDocument();
  });

  it('空结果态：以 NO_CONTEXT 语义呈现，且不渲染引用清单', async () => {
    askMock.mockResolvedValue(
      buildAnswer({
        answer: '知识库中未找到相关内容，无法基于现有资料回答该问题。',
        citations: [],
        degraded: true,
        degradeReason: NO_CONTEXT_REASON,
      }),
    );

    renderWithProviders(<ChatPage />, CHAT_ENTRY);

    await waitFor(() => expect(screen.getByText(zhCN.chat.noContextTitle)).toBeInTheDocument());
    expect(screen.getByText(new RegExp(zhCN.chat.noContextHint))).toBeInTheDocument();
    expect(screen.queryByTestId('chat-citations')).not.toBeInTheDocument();
    // 降级不得被渲染成正常回答
    expect(screen.queryByText(zhCN.chat.answerTitle)).not.toBeInTheDocument();
  });

  it('失败态：给出归一文案与重试入口，重试会重新发起请求', async () => {
    askMock.mockRejectedValue(
      new ApiError({ code: 'LLM_UNAVAILABLE', message: zhCN.errors.serviceUnavailable, status: 502 }),
    );

    renderWithProviders(<ChatPage />, CHAT_ENTRY);

    await waitFor(() => expect(screen.getByText(zhCN.errors.bannerTitle)).toBeInTheDocument());
    expect(screen.getByText(zhCN.errors.serviceUnavailable)).toBeInTheDocument();
    expect(screen.queryByTestId('chat-citations')).not.toBeInTheDocument();

    askMock.mockResolvedValue(buildAnswer());
    fireEvent.click(screen.getByRole('button', { name: zhCN.common.retry }));

    await waitFor(() => expect(screen.getByText(zhCN.chat.answerTitle)).toBeInTheDocument());
    expect(askMock).toHaveBeenCalledTimes(2);
  });

  it('缺少 ?q= 时给出返回首页的引导，且不发起请求', () => {
    renderWithProviders(<ChatPage />, ['/chat']);

    expect(screen.getByText(zhCN.chat.missingQuestion)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: zhCN.chat.backHome })).toBeInTheDocument();
    expect(askMock).not.toHaveBeenCalled();
  });
});
