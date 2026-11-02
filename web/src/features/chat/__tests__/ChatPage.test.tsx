/**
 * 多轮对话页测试（018-chat-page-layout）。
 *
 * 覆盖：
 * - 流式渲染、引用合并为"标号 + 文档名称"链接、不渲染片段正文；
 * - 检索为空独立成态、失败可重试、多轮共享会话标识；
 * - 刷新后状态保真（失败/降级不被洗白）、历史回显不带引用；
 * - 长会话先取最后一页；
 * - 复制、重新生成、清空二次确认、空对话态、回到最新。
 *
 * 这里 mock 掉接口层（不篡改全局 fetch），不访问任何后端。
 */

import { fireEvent, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError } from '@/api/client';
import { clearChatSession, getChatHistory } from '@/api/chatHistory';
import { fetchDocumentFile, listDocuments } from '@/api/documents';
import { askStream, NO_CONTEXT_REASON, type AskResult } from '@/api/retrieval';
import { zhCN } from '@/content/zh-CN';
import ChatPage from '@/features/chat/ChatPage';
import { renderWithProviders } from '@/test/render';

vi.mock('@/api/retrieval', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/retrieval')>();
  return { ...actual, askStream: vi.fn() };
});

vi.mock('@/api/chatHistory', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/chatHistory')>();
  return { ...actual, getChatHistory: vi.fn(), clearChatSession: vi.fn() };
});

vi.mock('@/api/documents', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/documents')>();
  return { ...actual, listDocuments: vi.fn(), fetchDocumentFile: vi.fn() };
});

const askStreamMock = vi.mocked(askStream);
const getChatHistoryMock = vi.mocked(getChatHistory);
const clearChatSessionMock = vi.mocked(clearChatSession);
const listDocumentsMock = vi.mocked(listDocuments);
const fetchDocumentFileMock = vi.mocked(fetchDocumentFile);

const QUESTION = '向量数据库存在哪里？';
const CHAT_ENTRY = [`/chat?q=${encodeURIComponent(QUESTION)}`];

function emptyHistory(total = 0) {
  return { sessionId: 'any', total, page: 0, size: 100, items: [] };
}

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
      {
        documentId: 620,
        docName: 'demo-doc-1',
        pipelineKey: 'main',
        chunkIndex: 7,
        score: 0.31,
        text: '这是同一文档的另一个片段。',
      },
      {
        documentId: 621,
        docName: 'demo-doc-2',
        pipelineKey: 'main',
        chunkIndex: 1,
        score: 0.28,
        text: '另一个文档的片段正文。',
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
  getChatHistoryMock.mockReset();
  getChatHistoryMock.mockResolvedValue(emptyHistory());
  clearChatSessionMock.mockReset();
  clearChatSessionMock.mockResolvedValue(undefined);
  listDocumentsMock.mockReset();
  listDocumentsMock.mockResolvedValue({ total: 0, items: [] });
  fetchDocumentFileMock.mockReset();
  sessionStorage.clear();
  localStorage.clear();
});

describe('ChatPage 对话工作区', () => {
  it('首轮：流式完成后引用合并为"标号 + 文档名称"，不展示片段正文', async () => {
    mockStreamingAnswer();

    renderWithProviders(<ChatPage />, CHAT_ENTRY);

    await waitFor(() => expect(screen.getByText(buildAnswer().answer)).toBeInTheDocument());

    const links = screen.getAllByTestId('chat-citation-link');
    expect(links).toHaveLength(2);
    expect(links[0].textContent).toContain('demo-doc-1');
    expect(links[0].textContent).toContain('[1]');
    expect(links[0].textContent).toContain('[2]');
    expect(links[1].textContent).toContain('demo-doc-2');
    expect(links[1].textContent).toContain('[3]');

    // 片段正文、段号、分数一律不得进入页面
    expect(screen.queryByText(/原文文件以键值形式保存在数据库中/)).toBeNull();
    expect(screen.queryByText(/第 3 段/)).toBeNull();
    expect(screen.queryByText(/0\.42/)).toBeNull();
  });

  it('NO_CONTEXT 独立成态，不渲染引用区', async () => {
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

    await waitFor(() => expect(screen.getByText(buildAnswer().answer)).toBeInTheDocument());
    expect(askStreamMock).toHaveBeenCalledTimes(2);
  });

  it('多轮：第二轮自动携带同一会话标识', async () => {
    mockStreamingAnswer();
    renderWithProviders(<ChatPage />, CHAT_ENTRY);
    await waitFor(() => expect(screen.getByText(buildAnswer().answer)).toBeInTheDocument());

    const SECOND = '那切片的粒度怎么配？';
    mockStreamingAnswer(buildAnswer({ answer: '按管线配置 chunk 上限与重叠。', citations: [] }));
    fireEvent.change(screen.getByPlaceholderText(zhCN.chat.inputPlaceholder), {
      target: { value: SECOND },
    });
    fireEvent.keyDown(screen.getByPlaceholderText(zhCN.chat.inputPlaceholder), { key: 'Enter' });

    await waitFor(() => expect(screen.getByText('按管线配置 chunk 上限与重叠。')).toBeInTheDocument());
    expect(screen.getAllByTestId('chat-message')).toHaveLength(2);

    const calls = askStreamMock.mock.calls;
    expect(calls[1][0].sessionId).toEqual(calls[0][0].sessionId);
  });

  it('刷新后失败与检索为空状态保持，且历史回显无引用区', async () => {
    getChatHistoryMock.mockResolvedValue({
      sessionId: 'any',
      total: 4,
      page: 0,
      size: 100,
      items: [
        { roundSeq: 1, role: 'USER', content: '第一问', status: 'SUCCESS', degradeReason: null, traceId: 't0', createdAt: null },
        { roundSeq: 1, role: 'AI', content: '半截回答', status: 'FAILED', degradeReason: null, traceId: 't0', createdAt: null },
        { roundSeq: 2, role: 'USER', content: '第二问', status: 'SUCCESS', degradeReason: null, traceId: 't1', createdAt: null },
        { roundSeq: 2, role: 'AI', content: '未找到相关内容。', status: 'SUCCESS', degradeReason: NO_CONTEXT_REASON, traceId: 't1', createdAt: null },
      ],
    });

    renderWithProviders(<ChatPage />, ['/chat']);

    await waitFor(() => expect(screen.getAllByTestId('chat-message')).toHaveLength(2));
    // 失败轮次仍可重试，而非伪装成正常回答
    expect(screen.getByText(zhCN.errors.bannerTitle)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: zhCN.common.retry })).toBeInTheDocument();
    // 检索为空仍单独成态
    expect(screen.getByText(zhCN.chat.noContextTitle)).toBeInTheDocument();
    // 历史没有引用清单 → 不渲染引用区
    expect(screen.queryByTestId('chat-citations')).not.toBeInTheDocument();
    // 不带 ?q= 不自动发问
    expect(askStreamMock).not.toHaveBeenCalled();
  });

  it('长会话先取最后一页：total=250 时请求 page=0 与 page=2', async () => {
    getChatHistoryMock.mockImplementation(async (_sessionId, page = 0) =>
      page === 0
        ? emptyHistory(250)
        : {
            sessionId: 'any',
            total: 250,
            page,
            size: 100,
            items: [
              { roundSeq: 126, role: 'USER', content: '最后一轮的问题', status: 'SUCCESS', degradeReason: null, traceId: 't', createdAt: null },
              { roundSeq: 126, role: 'AI', content: '最后一轮的回答', status: 'SUCCESS', degradeReason: null, traceId: 't', createdAt: null },
            ],
          },
    );

    renderWithProviders(<ChatPage />, ['/chat']);

    await waitFor(() => expect(screen.getByText('最后一轮的回答')).toBeInTheDocument());
    expect(getChatHistoryMock).toHaveBeenCalledWith(expect.any(String), 0, 100);
    expect(getChatHistoryMock).toHaveBeenCalledWith(expect.any(String), 2, 100);
  });

  it('生成中允许编辑草稿，发送位显示停止', async () => {
    askStreamMock.mockImplementation(
      (_params, handlers, signal) =>
        new Promise<AskResult>((_resolve, reject) => {
          handlers.onDelta('半段回答');
          signal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')));
        }),
    );

    renderWithProviders(<ChatPage />, CHAT_ENTRY);

    await waitFor(() => expect(screen.getByText(zhCN.chat.stopAnswer)).toBeInTheDocument());

    const textarea = screen.getByPlaceholderText(zhCN.chat.inputPlaceholder);
    expect(textarea).not.toBeDisabled();
    fireEvent.change(textarea, { target: { value: '下一问的草稿' } });
    expect((textarea as HTMLTextAreaElement).value).toBe('下一问的草稿');

    fireEvent.click(screen.getByRole('button', { name: zhCN.chat.stopAnswer }));

    await waitFor(() => expect(screen.getByText(zhCN.chat.stoppedHint)).toBeInTheDocument());
    expect(screen.getByRole('button', { name: zhCN.common.retry })).toBeInTheDocument();
  });

  it('复制回答后给出"已复制"反馈', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText },
    });

    mockStreamingAnswer();
    renderWithProviders(<ChatPage />, CHAT_ENTRY);
    await waitFor(() => expect(screen.getByText(buildAnswer().answer)).toBeInTheDocument());

    fireEvent.click(screen.getByRole('button', { name: zhCN.chat.copyAnswer }));

    await waitFor(() => expect(writeText).toHaveBeenCalledWith(buildAnswer().answer));
    expect(screen.getByText(zhCN.chat.copied)).toBeInTheDocument();
  });

  it('重新生成替换当前轮，不产生重复消息', async () => {
    mockStreamingAnswer();
    renderWithProviders(<ChatPage />, CHAT_ENTRY);
    await waitFor(() => expect(screen.getByText(buildAnswer().answer)).toBeInTheDocument());

    mockStreamingAnswer(buildAnswer({ answer: '重新生成的回答。', citations: [] }));
    fireEvent.click(screen.getByRole('button', { name: zhCN.chat.regenerate }));

    await waitFor(() => expect(screen.getByText('重新生成的回答。')).toBeInTheDocument());
    expect(screen.getAllByTestId('chat-message')).toHaveLength(1);
    expect(askStreamMock).toHaveBeenCalledTimes(2);
  });

  it('清空会话取消时不删除任何数据', async () => {
    mockStreamingAnswer();
    renderWithProviders(<ChatPage />, CHAT_ENTRY);
    await waitFor(() => expect(screen.getByText(buildAnswer().answer)).toBeInTheDocument());

    fireEvent.click(screen.getByRole('button', { name: zhCN.chat.clearConversation }));
    expect(await screen.findByText(zhCN.chat.clearConfirmBody)).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: zhCN.chat.clearConfirmCancel }));

    expect(clearChatSessionMock).not.toHaveBeenCalled();
    expect(screen.getAllByTestId('chat-message')).toHaveLength(1);
  });

  it('清空会话确认后调用级联删除并重置页面', async () => {
    mockStreamingAnswer();
    renderWithProviders(<ChatPage />, CHAT_ENTRY);
    await waitFor(() => expect(screen.getByText(buildAnswer().answer)).toBeInTheDocument());

    fireEvent.click(screen.getByRole('button', { name: zhCN.chat.clearConversation }));
    fireEvent.click(await screen.findByRole('button', { name: zhCN.chat.clearConfirmOk }));

    await waitFor(() => expect(clearChatSessionMock).toHaveBeenCalledTimes(1));
    expect(screen.queryByTestId('chat-message')).not.toBeInTheDocument();
  });

  it('空对话显示中性提示，不渲染消息', async () => {
    renderWithProviders(<ChatPage />, ['/chat']);

    await waitFor(() => expect(screen.getByTestId('chat-empty')).toBeInTheDocument());
    expect(screen.queryByTestId('chat-message')).not.toBeInTheDocument();
    expect(screen.getByTestId('chat-composer')).toBeInTheDocument();
  });

  it('上翻离开底部时出现"回到最新"，点击后恢复跟随', async () => {
    mockStreamingAnswer();
    renderWithProviders(<ChatPage />, CHAT_ENTRY);
    await waitFor(() => expect(screen.getByText(buildAnswer().answer)).toBeInTheDocument());

    const transcript = screen.getByTestId('chat-transcript');
    Object.defineProperty(transcript, 'scrollHeight', { configurable: true, value: 1000 });
    Object.defineProperty(transcript, 'clientHeight', { configurable: true, value: 400 });
    transcript.scrollTop = 0;
    fireEvent.scroll(transcript);

    const backToLatest = await screen.findByTestId('chat-back-to-latest');
    fireEvent.click(backToLatest);

    await waitFor(() =>
      expect(screen.queryByTestId('chat-back-to-latest')).not.toBeInTheDocument(),
    );
  });
});
