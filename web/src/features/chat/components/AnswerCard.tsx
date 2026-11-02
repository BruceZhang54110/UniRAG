/**
 * 回答卡片（018-chat-page-layout T018 / T031）。
 *
 * 四种状态共用卡片容器：生成中、完成、失败、检索为空降级。
 * - 完成态才出现操作行（复制 / 重新生成），失败态只给重试（FR-037）；
 * - 引用区由 `CitationLinks` 承载，空引用不渲染（FR-021）；
 * - 所有颜色取主题 token，明暗主题一致（FR-041~043）。
 */

import Skeleton from '@lobehub/ui/es/Skeleton/index';
import { Alert, Button, Text } from '@lobehub/ui/base-ui';
import { theme } from 'antd';
import { Copy, RefreshCw } from 'lucide-react';
import { useCallback, useEffect, useRef, useState, type CSSProperties } from 'react';

import { NO_CONTEXT_REASON } from '@/api/retrieval';
import ErrorBanner from '@/components/ErrorBanner';
import { zhCN } from '@/content/zh-CN';
import { groupCitations } from '@/features/chat/citations';
import CitationLinks from '@/features/chat/components/CitationLinks';
import type { ChatMessage } from '@/features/chat/types';

/** "已复制"反馈的展示时长 */
const COPY_FEEDBACK_MS = 2000;

export interface AnswerCardProps {
  message: ChatMessage;
  onRetry: () => void;
  onRegenerate: () => void;
}

export default function AnswerCard({ message, onRetry, onRegenerate }: AnswerCardProps) {
  const { token } = theme.useToken();
  const [copyState, setCopyState] = useState<'idle' | 'copied' | 'failed'>('idle');
  const timerRef = useRef<number | null>(null);

  useEffect(
    () => () => {
      if (timerRef.current !== null) window.clearTimeout(timerRef.current);
    },
    [],
  );

  const copy = useCallback(() => {
    const write = navigator.clipboard?.writeText?.(message.answer);
    if (!write) {
      setCopyState('failed');
      return;
    }

    write
      .then(() => {
        setCopyState('copied');
        if (timerRef.current !== null) window.clearTimeout(timerRef.current);
        timerRef.current = window.setTimeout(() => setCopyState('idle'), COPY_FEEDBACK_MS);
      })
      .catch(() => setCopyState('failed'));
  }, [message.answer]);

  if (message.status === 'streaming') {
    return (
      <div aria-busy="true" aria-label={zhCN.chat.loadingAnswer} data-testid="chat-answer-loading">
        {message.answer ? (
          <div style={cardStyle(token)}>
            <Text data-testid="chat-answer" style={textStyle}>
              {message.answer}
            </Text>
          </div>
        ) : (
          <div style={cardStyle(token)}>
            <Skeleton paragraph={{ rows: 3 }} title={false} />
          </div>
        )}
      </div>
    );
  }

  if (message.status === 'error') {
    return <ErrorBanner message={message.errorMessage ?? zhCN.errors.serviceUnavailable} onRetry={onRetry} />;
  }

  const noContext = message.degraded && message.degradeReason === NO_CONTEXT_REASON;
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

  const links = groupCitations(message.citations);

  return (
    <div data-testid="chat-answer" style={cardStyle(token)}>
      <Text style={textStyle}>{message.answer}</Text>

      <CitationLinks items={links} />

      <div data-testid="chat-actions" style={actionsStyle}>
        <Button icon={<Copy size={14} />} size="small" type="text" onClick={copy}>
          {copyState === 'copied' ? zhCN.chat.copied : zhCN.chat.copyAnswer}
        </Button>
        <Button icon={<RefreshCw size={14} />} size="small" type="text" onClick={onRegenerate}>
          {zhCN.chat.regenerate}
        </Button>
        {copyState === 'failed' ? (
          <Text data-testid="chat-copy-feedback" fontSize={12} type="danger">
            {zhCN.chat.copyFailed}
          </Text>
        ) : null}
      </div>
    </div>
  );
}

function cardStyle(token: ReturnType<typeof theme.useToken>['token']): CSSProperties {
  return {
    display: 'flex',
    flexDirection: 'column',
    gap: 14,
    padding: '14px 16px',
    border: `1px solid ${token.colorBorderSecondary}`,
    borderRadius: token.borderRadiusLG,
    background: token.colorBgContainer,
  };
}

const textStyle: CSSProperties = {
  whiteSpace: 'pre-wrap',
  wordBreak: 'break-word',
};

const actionsStyle: CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 4,
  flexWrap: 'wrap',
};
