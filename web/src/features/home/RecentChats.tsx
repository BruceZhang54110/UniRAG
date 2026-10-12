/**
 * 最近对话（T038 / FR-013、FR-014，ui-contract §2.6）。
 *
 * - 最多 6 条，展示问题摘要与相对时间；
 * - 点击恢复该会话：问答页当前只承接 `?q=`，因此以"带出原问题"的方式恢复；
 * - **无历史时整块不渲染**（不留空容器、不占位）。
 */

import { Text } from '@lobehub/ui/base-ui';
import type { CSSProperties } from 'react';
import { Link } from 'react-router';

import RelativeTime from '@/components/RelativeTime';
import { zhCN } from '@/content/zh-CN';
import type { ChatSessionSummary } from '@/hooks/useRecentChats';

export interface RecentChatsProps {
  items: ChatSessionSummary[];
}

export default function RecentChats({ items }: RecentChatsProps) {
  if (items.length === 0) return null;

  return (
    <section aria-label={zhCN.recentChats.title} style={containerStyle} data-testid="recent-chats">
      <Text fontSize={12} strong type="secondary">
        {zhCN.recentChats.title}
      </Text>
      <ul style={listStyle}>
        {items.map((item) => (
          <li key={item.id} data-testid="recent-chat-item" style={itemStyle}>
            <Link
              className="recent-chat-link"
              style={linkStyle}
              title={zhCN.recentChats.restore}
              to={`/chat?q=${encodeURIComponent(item.question)}`}
            >
              <Text ellipsis style={{ flex: 1 }} type="secondary">
                {item.question}
              </Text>
              <RelativeTime value={item.lastActiveAt} />
            </Link>
          </li>
        ))}
      </ul>
    </section>
  );
}

const containerStyle: CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 6,
  marginTop: 8,
};

const listStyle: CSSProperties = {
  listStyle: 'none',
  margin: 0,
  padding: 0,
};

const itemStyle: CSSProperties = {
  minWidth: 0,
};

const linkStyle: CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 12,
  padding: '6px 8px',
  borderRadius: 6,
};
