/**
 * 空对话态（018-chat-page-layout T028 / FR-030）。
 *
 * 中性提示 + 复用首页建议问题：不伪造示例问答，点击建议等同于直接提交。
 * 建议问题在知识库不可用时按 `SuggestionChips` 既有规则置灰并说明原因。
 */

import { Text } from '@lobehub/ui/base-ui';
import { theme } from 'antd';
import { useMemo, type CSSProperties } from 'react';

import { zhCN } from '@/content/zh-CN';
import SuggestionChips from '@/features/home/SuggestionChips';
import { buildSuggestions } from '@/features/home/state';
import { useHomeOverview } from '@/hooks/useHomeOverview';

export interface EmptyConversationProps {
  onSelect: (question: string) => void;
}

export default function EmptyConversation({ onSelect }: EmptyConversationProps) {
  const { token } = theme.useToken();
  const { overview, items } = useHomeOverview();
  const suggestions = useMemo(() => buildSuggestions(items, overview), [items, overview]);

  return (
    <section
      aria-label={zhCN.chat.emptyTitle}
      data-testid="chat-empty"
      style={{
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        gap: 16,
        padding: '48px 0',
        color: token.colorTextSecondary,
      }}
    >
      <Text strong style={titleStyle}>
        {zhCN.chat.emptyTitle}
      </Text>
      <Text type="secondary">{zhCN.chat.emptyHint}</Text>
      <SuggestionChips suggestions={suggestions} onSelect={onSelect} />
    </section>
  );
}

const titleStyle: CSSProperties = {
  fontSize: 18,
};
