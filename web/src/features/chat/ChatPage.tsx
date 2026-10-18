/**
 * 问答页（T032 / FR-013，ui-contract §2.4）：把 `/chat?q=<问题>` 提交给 `POST /api/v1/ask`，
 * 展示回答与引用片段（文档标识、片段序号、原文），覆盖加载 / 空结果 / 失败三态。
 *
 * 纪律：
 * - 回答与原文按**纯文本**原样展示（不走 Markdown 渲染），不转义、不二次加工；
 * - 检索为空（`degradeReason=NO_CONTEXT`）单独成一态，文案来自后端固定回答，不伪造内容；
 * - 失败只给已归一的中文文案 + 重试，绝不渲染为"知识库为空"。
 */

import Skeleton from '@lobehub/ui/es/Skeleton/index';
import { Alert, Button, Text } from '@lobehub/ui/base-ui';
import { ChatItem } from '@lobehub/ui/chat';
import { useCallback, useEffect, useState, type CSSProperties } from 'react';
import { useNavigate, useSearchParams } from 'react-router';

import { isAbortError, toApiErrorShape } from '@/api/client';
import { ask, NO_CONTEXT_REASON, type AskResult } from '@/api/retrieval';
import ErrorBanner from '@/components/ErrorBanner';
import { zhCN } from '@/content/zh-CN';

const HOME_HREF = '/';

type AskState = 'loading' | 'ready' | 'error';

export default function ChatPage() {
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const question = (searchParams.get('q') ?? '').trim();

  const [state, setState] = useState<AskState>('loading');
  const [result, setResult] = useState<AskResult | null>(null);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [reloadToken, setReloadToken] = useState(0);

  useEffect(() => {
    if (!question) return;

    const controller = new AbortController();
    setState('loading');
    setErrorMessage(null);

    ask({ question, signal: controller.signal })
      .then((answer) => {
        setResult(answer);
        setState('ready');
      })
      .catch((error: unknown) => {
        if (isAbortError(error)) return;
        setResult(null);
        setErrorMessage(toApiErrorShape(error).message);
        setState('error');
      });

    return () => controller.abort();
  }, [question, reloadToken]);

  const goHome = useCallback(() => {
    navigate(HOME_HREF);
  }, [navigate]);

  if (!question) {
    return (
      <div style={pageStyle}>
        <Alert
          description={zhCN.chat.missingQuestionHint}
          showIcon
          title={zhCN.chat.missingQuestion}
          type="info"
        />
        <div>
          <Button type="fill" onClick={goHome}>
            {zhCN.chat.backHome}
          </Button>
        </div>
      </div>
    );
  }

  const noContext = result?.degraded === true && result.degradeReason === NO_CONTEXT_REASON;

  return (
    <div style={pageStyle}>
      <ChatItem
        avatar={{ title: zhCN.chat.yourQuestion }}
        message={question}
        placement="right"
        showAvatar={false}
        showTitle
        renderMessage={() => <Text style={plainTextStyle}>{question}</Text>}
      />

      {state === 'loading' ? (
        <div aria-busy="true" aria-label={zhCN.chat.loadingAnswer} data-testid="chat-answer-loading">
          <Skeleton active paragraph={{ rows: 3 }} title={false} />
        </div>
      ) : null}

      {state === 'error' ? (
        <ErrorBanner
          message={errorMessage ?? zhCN.errors.serviceUnavailable}
          onRetry={() => setReloadToken((token) => token + 1)}
        />
      ) : null}

      {state === 'ready' && result ? (
        noContext ? (
          <Alert
            description={`${result.answer} ${zhCN.chat.noContextHint}`}
            showIcon
            title={zhCN.chat.noContextTitle}
            type="warning"
          />
        ) : (
          <>
            <ChatItem
              avatar={{ title: zhCN.chat.answerTitle }}
              message={result.answer}
              placement="left"
              showAvatar={false}
              showTitle
              renderMessage={() => <Text style={plainTextStyle}>{result.answer}</Text>}
            />
            <CitationList items={result.citations} />
          </>
        )
      ) : null}

      <div>
        <Button onClick={goHome}>{zhCN.chat.backHome}</Button>
      </div>
    </div>
  );
}

function CitationList({ items }: { items: AskResult['citations'] }) {
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
