/**
 * 建议问题（T036 / FR-007，ui-contract §2.3）。
 *
 * 点击与手动提交等价（由 `onSelect` 交给首页走同一条提交路径）；
 * 知识库不可提问时全部置灰并说明原因，**不得提交**。
 */

import { Button, Text } from '@lobehub/ui/base-ui';
import type { CSSProperties } from 'react';

import { zhCN } from '@/content/zh-CN';
import type { Suggestion } from '@/features/home/state';

export interface SuggestionChipsProps {
  suggestions: Suggestion[];
  onSelect: (question: string) => void;
}

export default function SuggestionChips({ suggestions, onSelect }: SuggestionChipsProps) {
  if (suggestions.length === 0) return null;

  const enabled = suggestions.some((item) => item.enabled);

  return (
    <section className="suggestion-chips" aria-label={zhCN.suggestions.title} style={containerStyle}>
      <Text fontSize={12} type="secondary">
        {zhCN.suggestions.title}
      </Text>
      <div style={chipsStyle}>
        {suggestions.map((suggestion) => (
          <Button
            key={suggestion.id}
            data-testid="suggestion-chip"
            disabled={!suggestion.enabled}
            shape="round"
            size="small"
            type="default"
            onClick={() => onSelect(suggestion.text)}
          >
            {suggestion.text}
          </Button>
        ))}
      </div>
      {!enabled ? (
        <Text data-testid="suggestion-disabled-hint" fontSize={12} type="secondary">
          {zhCN.suggestions.disabled}
        </Text>
      ) : null}
    </section>
  );
}

const containerStyle: CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 8,
  alignItems: 'center',
};

const chipsStyle: CSSProperties = {
  display: 'flex',
  flexWrap: 'wrap',
  gap: 8,
  justifyContent: 'center',
};
