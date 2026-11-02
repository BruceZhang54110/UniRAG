/**
 * 多轮对话页（018-chat-page-layout，spec FR-001~048）。
 *
 * 布局：顶栏 + 独立滚动的消息区 + 固定底部输入区。页面根元素带
 * `data-page-layout="flush"`，ConsoleShell 据此取消 main 的内边距与滚动，
 * 使输入区永远停在可视区域底部（FR-001~002）。
 *
 * 数据：
 * - 历史先取最后一页保证最新内容可见，向上滚动再补更早页（FR-044~048）；
 * - 历史状态按 `status`/`degradeReason` 还原，刷新后不洗白失败与降级（FR-025~028）；
 * - 历史不含引用清单，因此回显不带引用（FR-028）。
 *
 * 纪律（沿用 015/016）：
 * - 回答与原文按**纯文本**原样展示，不走 Markdown 渲染；
 * - 失败只给已归一的文案 + 重试；检索为空单独成态，不伪造内容。
 */

import { Button, Modal, Text } from '@lobehub/ui/base-ui';
import { theme } from 'antd';
import { useCallback, useEffect, useMemo, useRef, useState, type CSSProperties } from 'react';
import { useNavigate, useSearchParams } from 'react-router';

import { isAbortError, toApiErrorShape } from '@/api/client';
import {
  clearChatSession,
  getChatHistory,
  type ChatHistoryMessage,
} from '@/api/chatHistory';
import { askStream, type AskResult } from '@/api/retrieval';
import AskComposer from '@/components/AskComposer';
import ErrorBanner from '@/components/ErrorBanner';
import { zhCN } from '@/content/zh-CN';
import AnswerCard from '@/features/chat/components/AnswerCard';
import ChatHeader from '@/features/chat/components/ChatHeader';
import EmptyConversation from '@/features/chat/components/EmptyConversation';
import {
  HISTORY_PAGE_SIZE,
  lastPageOf,
  mergeEarlierPages,
  toMessages,
} from '@/features/chat/history';
import { shouldLoadEarlier } from '@/features/chat/scroll';
import type { ChatMessage } from '@/features/chat/types';
import { useStickToBottom } from '@/features/chat/useStickToBottom';

const HOME_HREF = '/';

/** 会话记忆标识（015-chat-memory）：每个浏览器标签页一个会话，刷新保留、新标签页开新会话。 */
const SESSION_STORAGE_KEY = 'unirag.chat.session-id';

/** 新会话在服务端不存在时的错误码：静默按空会话处理（FR-028 / 既有行为） */
const SESSION_NOT_FOUND = 'SESSION_NOT_FOUND';

function sessionIdOf(): string {
  let id = sessionStorage.getItem(SESSION_STORAGE_KEY);
  if (!id) {
    id = crypto.randomUUID();
    sessionStorage.setItem(SESSION_STORAGE_KEY, id);
  }
  return id;
}

type HistoryStatus = 'loading' | 'ready' | 'error';

interface EarlierState {
  loading: boolean;
  failed: boolean;
  hasEarlier: boolean;
}

export default function ChatPage() {
  const { token } = theme.useToken();
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const initialQuestion = (searchParams.get('q') ?? '').trim();

  const sessionIdRef = useRef(sessionIdOf());
  const [historyItems, setHistoryItems] = useState<ChatHistoryMessage[]>([]);
  const [liveMessages, setLiveMessages] = useState<ChatMessage[]>([]);
  const [historyStatus, setHistoryStatus] = useState<HistoryStatus>('loading');
  const [earlier, setEarlier] = useState<EarlierState>({
    loading: false,
    failed: false,
    hasEarlier: false,
  });
  const earliestPageRef = useRef(0);
  const earlierLoadingRef = useRef(false);

  const [draft, setDraft] = useState('');
  const [streaming, setStreaming] = useState(false);
  const streamingRef = useRef(false);
  const [clearOpen, setClearOpen] = useState(false);
  const [clearFailed, setClearFailed] = useState(false);

  const abortRef = useRef<AbortController | null>(null);
  /** 流式增量的就地累加器：onDelta 跨 await 触发，闭包里的 state 是陈旧快照 */
  const answerAccumRef = useRef<Map<string, string>>(new Map());
  const bootstrappedRef = useRef(false);

  const { containerRef, following, scrollToLatest, followContent } = useStickToBottom();

  const historyMessages = useMemo(() => toMessages(historyItems), [historyItems]);
  const messages = useMemo(
    () => [...historyMessages, ...liveMessages],
    [historyMessages, liveMessages],
  );

  const title = messages[0]?.question.trim() || zhCN.chat.untitledConversation;

  /** 先取最后一页：保证 100 轮以上的会话打开即见最新内容（FR-044） */
  const loadLatest = useCallback(async () => {
    setHistoryStatus('loading');
    try {
      const firstPage = await getChatHistory(sessionIdRef.current, 0, HISTORY_PAGE_SIZE);
      const lastPage = lastPageOf(firstPage.total, HISTORY_PAGE_SIZE);
      const target =
        lastPage === 0
          ? firstPage
          : await getChatHistory(sessionIdRef.current, lastPage, HISTORY_PAGE_SIZE);

      earliestPageRef.current = lastPage;
      setHistoryItems(target.items);
      setEarlier({ loading: false, failed: false, hasEarlier: lastPage > 0 });
      setHistoryStatus('ready');
      requestAnimationFrame(() => scrollToLatest());
    } catch (error: unknown) {
      if (toApiErrorShape(error).code === SESSION_NOT_FOUND) {
        // 新会话在服务端尚不存在：按空会话处理，不打扰用户
        earliestPageRef.current = 0;
        setHistoryItems([]);
        setEarlier({ loading: false, failed: false, hasEarlier: false });
        setHistoryStatus('ready');
        return;
      }
      setHistoryStatus('error');
    }
  }, [scrollToLatest]);

  useEffect(() => {
    void loadLatest();
  }, [loadLatest]);

  /** 向上滚动加载更早历史；合并后补偿滚动高度，保持阅读位置（FR-045~046） */
  const loadEarlier = useCallback(async () => {
    if (earliestPageRef.current <= 0 || earlierLoadingRef.current) return;

    earlierLoadingRef.current = true;
    setEarlier((current) => ({ ...current, loading: true, failed: false }));

    const container = containerRef.current;
    const previousHeight = container?.scrollHeight ?? 0;
    const previousTop = container?.scrollTop ?? 0;

    try {
      const page = await getChatHistory(
        sessionIdRef.current,
        earliestPageRef.current - 1,
        HISTORY_PAGE_SIZE,
      );
      earliestPageRef.current -= 1;
      setHistoryItems((current) => mergeEarlierPages(current, page.items));
      setEarlier({
        loading: false,
        failed: false,
        hasEarlier: earliestPageRef.current > 0,
      });
      requestAnimationFrame(() => {
        const element = containerRef.current;
        if (!element) return;
        element.scrollTop = previousTop + (element.scrollHeight - previousHeight);
      });
    } catch {
      setEarlier((current) => ({ ...current, loading: false, failed: true }));
    } finally {
      earlierLoadingRef.current = false;
    }
  }, [containerRef]);

  const handleTranscriptScroll = useCallback(() => {
    const element = containerRef.current;
    if (element && shouldLoadEarlier(element.scrollTop)) void loadEarlier();
  }, [containerRef, loadEarlier]);

  const patchLive = useCallback((id: string, patch: Partial<ChatMessage>) => {
    setLiveMessages((current) =>
      current.map((message) => (message.id === id ? { ...message, ...patch } : message)),
    );
  }, []);

  const send = useCallback(
    async (question: string) => {
      if (streamingRef.current) return;

      const id = crypto.randomUUID();
      answerAccumRef.current.set(id, '');
      setLiveMessages((current) => [
        ...current,
        {
          id,
          question,
          answer: '',
          citations: [],
          degraded: false,
          degradeReason: null,
          status: 'streaming',
        },
      ]);
      streamingRef.current = true;
      setStreaming(true);
      scrollToLatest();

      const controller = new AbortController();
      abortRef.current = controller;

      try {
        const result: AskResult = await askStream(
          { question, sessionId: sessionIdRef.current },
          {
            onDelta: (delta) => {
              const accumulated = (answerAccumRef.current.get(id) ?? '') + delta;
              answerAccumRef.current.set(id, accumulated);
              patchLive(id, { answer: accumulated });
              followContent();
            },
          },
          controller.signal,
        );
        patchLive(id, {
          answer: result.answer,
          citations: result.citations,
          degraded: result.degraded,
          degradeReason: result.degradeReason,
          status: 'done',
        });
        followContent();
      } catch (error: unknown) {
        if (isAbortError(error)) {
          // 用户主动停止：该轮不入记忆（后端 FR-008），本地标记失败可重试
          patchLive(id, { status: 'error', errorMessage: zhCN.chat.stoppedHint });
        } else {
          patchLive(id, { status: 'error', errorMessage: toApiErrorShape(error).message });
        }
      } finally {
        abortRef.current = null;
        streamingRef.current = false;
        setStreaming(false);
      }
    },
    [followContent, patchLive, scrollToLatest],
  );

  useEffect(() => {
    if (initialQuestion && !bootstrappedRef.current) {
      bootstrappedRef.current = true;
      void send(initialQuestion);
    }
  }, [initialQuestion, send]);

  const submit = useCallback(() => {
    const question = draft.trim();
    if (!question || streamingRef.current) return;
    setDraft('');
    void send(question);
  }, [draft, send]);

  const stopStreaming = useCallback(() => {
    abortRef.current?.abort();
  }, []);

  const removeLive = useCallback((id: string) => {
    setLiveMessages((current) => current.filter((message) => message.id !== id));
  }, []);

  /** 重试与重新生成同路径：移除当前轮后以同一问题重发，页面不出现重复消息（FR-036） */
  const retry = useCallback(
    (message: ChatMessage) => {
      removeLive(message.id);
      void send(message.question);
    },
    [removeLive, send],
  );

  const resetConversation = useCallback(() => {
    abortRef.current?.abort();
    sessionStorage.removeItem(SESSION_STORAGE_KEY);
    sessionIdRef.current = sessionIdOf();
    answerAccumRef.current.clear();
    earliestPageRef.current = 0;
    earlierLoadingRef.current = false;
    setLiveMessages([]);
    setHistoryItems([]);
    setEarlier({ loading: false, failed: false, hasEarlier: false });
    setHistoryStatus('ready');
    setDraft('');
    setClearFailed(false);
    setClearOpen(false);
  }, []);

  const openClear = useCallback(() => {
    setClearFailed(false);
    setClearOpen(true);
  }, []);

  const confirmClear = useCallback(() => {
    abortRef.current?.abort();
    clearChatSession(sessionIdRef.current)
      .then(() => resetConversation())
      .catch(() => setClearFailed(true));
  }, [resetConversation]);

  const goHome = useCallback(() => {
    navigate(HOME_HREF);
  }, [navigate]);

  const askFromEmpty = useCallback(
    (question: string) => {
      if (streamingRef.current) return;
      void send(question.trim());
    },
    [send],
  );

  const showEmpty = messages.length === 0 && historyStatus !== 'loading';

  return (
    <div className="chat-page" data-page-layout="flush">
      <ChatHeader title={title} onClear={openClear} onHome={goHome} onNew={resetConversation} />

      <div style={transcriptWrapStyle}>
        <div
          aria-label={zhCN.chat.messagesLabel}
          className="chat-transcript"
          data-testid="chat-transcript"
          ref={containerRef}
          tabIndex={0}
          onScroll={handleTranscriptScroll}
        >
          <div className="chat-transcript-inner">
            {earlier.hasEarlier ? (
              <div data-testid="chat-load-earlier" style={earlierStyle}>
                {earlier.failed ? (
                  <>
                    <Text type="secondary">{zhCN.chat.loadEarlierFailed}</Text>
                    <Button size="small" type="text" onClick={() => void loadEarlier()}>
                      {zhCN.common.retry}
                    </Button>
                  </>
                ) : earlier.loading ? (
                  <Text type="secondary">{zhCN.chat.loadEarlier}</Text>
                ) : null}
              </div>
            ) : null}

            {historyStatus === 'error' ? (
              <ErrorBanner message={zhCN.chat.historyLoadFailed} onRetry={() => void loadLatest()} />
            ) : null}

            {historyStatus === 'loading' && messages.length === 0 ? (
              <Text type="secondary">{zhCN.common.loading}</Text>
            ) : null}

            {showEmpty ? <EmptyConversation onSelect={askFromEmpty} /> : null}

            {messages.map((message) => (
              <section data-testid="chat-message" key={message.id} style={messageGroupStyle}>
                <div data-testid="chat-question" style={questionStyle(token)}>
                  {message.question}
                </div>
                <AnswerCard
                  message={message}
                  onRegenerate={() => retry(message)}
                  onRetry={() => retry(message)}
                />
              </section>
            ))}
          </div>
        </div>

        {!following ? (
          <Button
            data-testid="chat-back-to-latest"
            shape="round"
            size="small"
            style={backToLatestStyle}
            onClick={() => scrollToLatest({ smooth: true })}
          >
            {zhCN.chat.backToLatest}
          </Button>
        ) : null}
      </div>

      <div className="chat-composer-area">
        <div style={composerWrapStyle}>
          <AskComposer
            ariaLabel={zhCN.chat.inputPlaceholder}
            loading={streaming}
            placeholder={zhCN.chat.inputPlaceholder}
            testId="chat-composer"
            value={draft}
            onChange={setDraft}
            onSend={submit}
            onStop={stopStreaming}
          />
          <Text style={footerNoteStyle} type="secondary">
            {zhCN.chat.footerNote}
          </Text>
        </div>
      </div>

      <Modal
        cancelText={zhCN.chat.clearConfirmCancel}
        okText={zhCN.chat.clearConfirmOk}
        open={clearOpen}
        title={zhCN.chat.clearConfirmTitle}
        onCancel={() => setClearOpen(false)}
        onOk={confirmClear}
      >
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          <Text>{zhCN.chat.clearConfirmBody}</Text>
          {clearFailed ? <Text type="danger">{zhCN.chat.clearFailed}</Text> : null}
        </div>
      </Modal>
    </div>
  );
}

const transcriptWrapStyle: CSSProperties = {
  position: 'relative',
  display: 'flex',
  flex: 1,
  flexDirection: 'column',
  minHeight: 0,
};

const messageGroupStyle: CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 10,
};

const earlierStyle: CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  gap: 8,
  minHeight: 24,
};

const backToLatestStyle: CSSProperties = {
  position: 'absolute',
  bottom: 12,
  left: '50%',
  transform: 'translateX(-50%)',
  zIndex: 1,
};

const composerWrapStyle: CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 8,
  width: '100%',
  maxWidth: 880,
  margin: '0 auto',
};

const footerNoteStyle: CSSProperties = {
  fontSize: 12,
  textAlign: 'center',
};

function questionStyle(token: ReturnType<typeof theme.useToken>['token']): CSSProperties {
  return {
    alignSelf: 'flex-end',
    maxWidth: '80%',
    padding: '10px 14px',
    borderRadius: token.borderRadiusLG,
    background: token.colorFillTertiary,
    whiteSpace: 'pre-wrap',
    wordBreak: 'break-word',
  };
}
