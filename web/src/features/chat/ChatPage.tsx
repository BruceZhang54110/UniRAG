/**
 * 多轮对话页（016-sse-chat-streaming，FR-005/006/010，ui-contract §2.4 升级版）：
 * 页内输入框连续发送提问，回答以 SSE 流式逐段渲染；历史消息按序回显并持久化到
 * localStorage（刷新可见，同一会话标识）；流式期间可停止（该轮不入记忆）、失败可重试；
 * 上一轮流式未结束时禁止新提交（会话内串行）。
 *
 * 纪律（沿用 015 前约定）：
 * - 回答与原文按**纯文本**原样展示（不走 Markdown 渲染），不转义、不二次加工；
 * - 检索为空（`degradeReason=NO_CONTEXT`）单独成一态，文案来自后端固定回答，不伪造内容；
 * - 失败只给已归一的文案 + 重试，绝不渲染为"知识库为空"。
 */

import Skeleton from '@lobehub/ui/es/Skeleton/index';
import { Alert, Button, Text } from '@lobehub/ui/base-ui';
import { ChatItem } from '@lobehub/ui/chat';
import { ChatInputAreaInner, ChatSendButton } from '@lobehub/ui/es/chat/ChatInputArea/index';
import { useCallback, useEffect, useRef, useState, type CSSProperties } from 'react';
import { useNavigate, useSearchParams } from 'react-router';

import { isAbortError, toApiErrorShape } from '@/api/client';
import { askStream, NO_CONTEXT_REASON, type AskResult, type Citation } from '@/api/retrieval';
import ErrorBanner from '@/components/ErrorBanner';
import { zhCN } from '@/content/zh-CN';

const HOME_HREF = '/';

/** 会话记忆标识（015-chat-memory）：每个浏览器标签页一个会话，刷新保留、新标签页开新会话。 */
const SESSION_STORAGE_KEY = 'unirag.chat.session-id';

function sessionIdOf(): string {
  let id = sessionStorage.getItem(SESSION_STORAGE_KEY);
  if (!id) {
    id = crypto.randomUUID();
    sessionStorage.setItem(SESSION_STORAGE_KEY, id);
  }
  return id;
}

const historyKey = (sessionId: string) => `unirag.chat.history.${sessionId}`;

type MessageStatus = 'streaming' | 'done' | 'error';

interface ChatMessage {
  id: string;
  question: string;
  answer: string;
  citations: Citation[];
  degraded: boolean;
  degradeReason: string | null;
  status: MessageStatus;
  /** status=error 时的已归一失败文案 */
  errorMessage?: string;
}

function loadHistory(sessionId: string): ChatMessage[] {
  try {
    const raw = localStorage.getItem(historyKey(sessionId));
    if (!raw) return [];
    const parsed = JSON.parse(raw) as ChatMessage[];
    if (!Array.isArray(parsed)) return [];
    // 刷新时把未完成的流式轮次恢复为可重试的失败态
    return parsed.map((message) =>
      message.status === 'streaming' ? { ...message, status: 'error' as const } : message,
    );
  } catch {
    return [];
  }
}

export default function ChatPage() {
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const initialQuestion = (searchParams.get('q') ?? '').trim();

  const sessionIdRef = useRef(sessionIdOf());
  const [messages, setMessages] = useState<ChatMessage[]>(() => loadHistory(sessionIdRef.current));
  const [draft, setDraft] = useState('');
  const [streaming, setStreaming] = useState(false);
  const abortRef = useRef<AbortController | null>(null);
  /** 流式增量的就地累加器：onDelta 回调跨 await 触发，闭包里的 state 是陈旧快照，必须用 ref */
  const answerAccumRef = useRef<Map<string, string>>(new Map());
  // ?q= 进入时只自动发送一次（后续不随路由参数变化重复触发）
  const bootstrappedRef = useRef(false);

  const writeHistory = useCallback((next: ChatMessage[]) => {
    localStorage.setItem(historyKey(sessionIdRef.current), JSON.stringify(next));
  }, []);

  const patchMessage = useCallback(
    (id: string, patch: Partial<ChatMessage>) => {
      setMessages((current) => {
        const next = current.map((message) =>
          message.id === id ? { ...message, ...patch } : message,
        );
        writeHistory(next);
        return next;
      });
    },
    [writeHistory],
  );

  const appendMessage = useCallback(
    (message: ChatMessage) => {
      setMessages((current) => {
        const next = [...current, message];
        writeHistory(next);
        return next;
      });
    },
    [writeHistory],
  );

  const removeMessage = useCallback(
    (id: string) => {
      setMessages((current) => {
        const next = current.filter((message) => message.id !== id);
        writeHistory(next);
        return next;
      });
    },
    [writeHistory],
  );

  const send = useCallback(
    async (question: string) => {
      if (streaming) return;   // 会话内串行：上一轮流式未结束禁止新提交
      const id = crypto.randomUUID();
      answerAccumRef.current.set(id, '');
      appendMessage({
        id,
        question,
        answer: '',
        citations: [],
        degraded: false,
        degradeReason: null,
        status: 'streaming',
      });
      setStreaming(true);

      const controller = new AbortController();
      abortRef.current = controller;
      try {
        const result: AskResult = await askStream(
          { question, sessionId: sessionIdRef.current },
          {
            onDelta: (delta) => {
              const accumulated = (answerAccumRef.current.get(id) ?? '') + delta;
              answerAccumRef.current.set(id, accumulated);
              patchMessage(id, { answer: accumulated });
            },
          },
          controller.signal,
        );
        patchMessage(id, {
          answer: result.answer,
          citations: result.citations,
          degraded: result.degraded,
          degradeReason: result.degradeReason,
          status: 'done',
        });
      } catch (error: unknown) {
        if (isAbortError(error)) {
          // 用户主动停止：该轮不入记忆（后端 FR-008），本地标记失败可重试
          patchMessage(id, { status: 'error', errorMessage: zhCN.chat.stoppedHint });
        } else {
          patchMessage(id, {
            status: 'error',
            errorMessage: toApiErrorShape(error).message,
          });
        }
      } finally {
        abortRef.current = null;
        setStreaming(false);
      }
    },
    [streaming, appendMessage, patchMessage],
  );

  useEffect(() => {
    if (initialQuestion && !bootstrappedRef.current) {
      bootstrappedRef.current = true;
      void send(initialQuestion);
    }
  }, [initialQuestion, send]);

  const goHome = useCallback(() => {
    navigate(HOME_HREF);
  }, [navigate]);

  const stopStreaming = useCallback(() => {
    abortRef.current?.abort();
  }, []);

  const submit = useCallback(() => {
    const question = draft.trim();
    if (!question || streaming) return;
    setDraft('');
    void send(question);
  }, [draft, streaming, send]);

  const retry = useCallback(
    (message: ChatMessage) => {
      // 旧消息先移除再重发，避免列表里出现两条同问题消息
      removeMessage(message.id);
      void send(message.question);
    },
    [removeMessage, send],
  );

  const startNew = useCallback(() => {
    abortRef.current?.abort();
    sessionStorage.removeItem(SESSION_STORAGE_KEY);
    localStorage.removeItem(historyKey(sessionIdRef.current));
    sessionIdRef.current = sessionIdOf();
    setMessages([]);
    setDraft('');
  }, []);

  return (
    <div style={pageStyle}>
      {messages.map((message) => (
        <section key={message.id} data-testid="chat-message">
          <ChatItem
            avatar={{ title: zhCN.chat.yourQuestion }}
            message={message.question}
            placement="right"
            showAvatar={false}
            showTitle
            renderMessage={() => <Text style={plainTextStyle}>{message.question}</Text>}
          />
          <MessageBody
            message={message}
            onRetry={() => retry(message)}
          />
        </section>
      ))}

      <div style={composerStyle}>
        <div style={inputWrapStyle}>
          <ChatInputAreaInner
            disabled={streaming}
            onChange={(event) => setDraft(event.target.value)}
            onSend={submit}
            placeholder={streaming ? '' : zhCN.chat.inputPlaceholder}
            value={draft}
          />
        </div>
        {streaming ? (
          <Button onClick={stopStreaming}>{zhCN.chat.stopAnswer}</Button>
        ) : (
          <ChatSendButton
            onSend={submit}
            texts={{ send: zhCN.chat.sendQuestion, warp: zhCN.chat.sendWarp }}
          />
        )}
        <Button onClick={startNew}>{zhCN.chat.newConversation}</Button>
        <Button onClick={goHome}>{zhCN.chat.backHome}</Button>
      </div>
    </div>
  );
}

function MessageBody({ message, onRetry }: { message: ChatMessage; onRetry: () => void }) {
  if (message.status === 'streaming') {
    return (
      <div aria-busy="true" aria-label={zhCN.chat.loadingAnswer} data-testid="chat-answer-loading">
        {message.answer ? (
          <ChatItem
            avatar={{ title: zhCN.chat.answerTitle }}
            message={message.answer}
            placement="left"
            showAvatar={false}
            showTitle
            renderMessage={() => <Text style={plainTextStyle}>{message.answer}</Text>}
          />
        ) : (
          <Skeleton active paragraph={{ rows: 3 }} title={false} />
        )}
      </div>
    );
  }

  if (message.status === 'error') {
    return (
      <ErrorBanner message={message.errorMessage ?? zhCN.errors.serviceUnavailable} onRetry={onRetry} />
    );
  }

  const noContext = message.degraded === true && message.degradeReason === NO_CONTEXT_REASON;
  if (noContext) {
    return (
      <Alert
        description={`${message.answer} ${zhCN.chat.noContextHint}`}
        showIcon
        title={zhCN.chat.noContextTitle}
        type="warning"
      />
    );
  }

  return (
    <>
      <ChatItem
        avatar={{ title: zhCN.chat.answerTitle }}
        message={message.answer}
        placement="left"
        showAvatar={false}
        showTitle
        renderMessage={() => <Text style={plainTextStyle}>{message.answer}</Text>}
      />
      <CitationList items={message.citations} />
    </>
  );
}

function CitationList({ items }: { items: Citation[] }) {
  if (items.length === 0) return null;

  return (
    <section aria-label={zhCN.chat.citationsTitle} data-testid="chat-citations" style={citationsStyle}>
      <Text strong>{zhCN.chat.citationsTitle}</Text>
      <ul style={listStyle}>
        {items.map((citation, index) => (
          <li
            key={`${citation.documentId}-${citation.pipelineKey}-${citation.chunkIndex}`}
            data-testid="chat-citation"
            style={citationStyle}
          >
            <Text fontSize={12} type="secondary">
              {zhCN.chat.citationSource
                .replace('{index}', String(index + 1))
                .replace('{docName}', citation.docName)
                .replace('{chunkIndex}', String(citation.chunkIndex))}
            </Text>
            <Text style={plainTextStyle}>{citation.text}</Text>
          </li>
        ))}
      </ul>
    </section>
  );
}

const pageStyle: CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 20,
  width: '100%',
  maxWidth: 880,
  margin: '0 auto',
  padding: '24px 0',
};

const composerStyle: CSSProperties = {
  display: 'flex',
  gap: 8,
  alignItems: 'flex-end',
};

const inputWrapStyle: CSSProperties = {
  flex: 1,
};

/** 用户输入与模型输出一律按纯文本原样展示（保留换行，不解析标记） */
const plainTextStyle: CSSProperties = {
  whiteSpace: 'pre-wrap',
  wordBreak: 'break-word',
};

const citationsStyle: CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 8,
};

const listStyle: CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 12,
  listStyle: 'none',
  margin: 0,
  padding: 0,
};

const citationStyle: CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 4,
  padding: '10px 12px',
  borderRadius: 8,
  background: 'rgba(127, 127, 127, 0.06)',
};
