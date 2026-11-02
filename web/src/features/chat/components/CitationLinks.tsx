/**
 * 引用区（018-chat-page-layout T017 / FR-016~024）。
 *
 * 只展示"标号 + 文档名称"：片段正文、段号、相似度分数都不进入 DOM。
 * 宽屏多列、窄屏单列；空引用不渲染容器（不留占位）。
 */

import { Text } from '@lobehub/ui/base-ui';
import { theme } from 'antd';
import type { CSSProperties } from 'react';

import { zhCN } from '@/content/zh-CN';
import type { CitationLink } from '@/features/chat/citations';

import OriginalDocumentLink from './OriginalDocumentLink';

export interface CitationLinksProps {
  items: CitationLink[];
}

export default function CitationLinks({ items }: CitationLinksProps) {
  const { token } = theme.useToken();

  if (items.length === 0) return null;

  return (
    <section aria-label={zhCN.chat.citationsTitle} data-testid="chat-citations" style={containerStyle}>
      <Text fontSize={12} type="secondary">
        {`${zhCN.chat.citationsTitle}${zhCN.chat.citationsHint}`}
      </Text>

      <ul style={listStyle}>
        {items.map((item) => (
          <li
            key={item.key}
            style={{
              ...itemStyle,
              border: `1px solid ${token.colorBorderSecondary}`,
              borderRadius: token.borderRadius,
              background: token.colorFillQuaternary,
            }}
          >
            <OriginalDocumentLink docName={item.docName} markers={item.markers} name={item.name} />
          </li>
        ))}
      </ul>
    </section>
  );
}

const containerStyle: CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 8,
};

const listStyle: CSSProperties = {
  display: 'grid',
  gridTemplateColumns: 'repeat(auto-fill, minmax(280px, 1fr))',
  gap: 8,
  margin: 0,
  padding: 0,
  listStyle: 'none',
};

const itemStyle: CSSProperties = {
  display: 'flex',
  minWidth: 0,
  padding: '8px 12px',
};
