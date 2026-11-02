/**
 * 对话页顶栏（018-chat-page-layout T026 / FR-029 / FR-014）。
 *
 * 对话级操作集中在此，输入行只保留输入与发送（FR-012）。
 * 标题取首轮问题；无对话时显示通用标题。
 */

import { Button, Text } from '@lobehub/ui/base-ui';
import { theme } from 'antd';
import { Eraser, Home, Plus } from 'lucide-react';
import type { CSSProperties } from 'react';

import { zhCN } from '@/content/zh-CN';

export interface ChatHeaderProps {
  title: string;
  onNew: () => void;
  onClear: () => void;
  onHome: () => void;
}

export default function ChatHeader({ title, onNew, onClear, onHome }: ChatHeaderProps) {
  const { token } = theme.useToken();

  const style: CSSProperties = {
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: 12,
    width: '100%',
    maxWidth: 880,
    margin: '0 auto',
    padding: '12px 16px',
    borderBottom: `1px solid ${token.colorBorderSecondary}`,
  };

  return (
    <header data-testid="chat-header" style={style}>
      <div style={titleStyle}>
        <Text type="secondary">{zhCN.chat.headerPrefix}</Text>
        <Text type="secondary">{'/'}</Text>
        <Text data-testid="chat-header-title" ellipsis strong title={title}>
          {title}
        </Text>
      </div>

      <div style={actionStyle}>
        <Button icon={<Plus size={14} />} size="small" onClick={onNew}>
          {zhCN.chat.newConversation}
        </Button>
        <Button icon={<Eraser size={14} />} size="small" onClick={onClear}>
          {zhCN.chat.clearConversation}
        </Button>
        <Button icon={<Home size={14} />} size="small" type="text" onClick={onHome}>
          {zhCN.chat.backHomeShort}
        </Button>
      </div>
    </header>
  );
}

const titleStyle: CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 6,
  minWidth: 0,
};

const actionStyle: CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 4,
  flex: 'none',
};
