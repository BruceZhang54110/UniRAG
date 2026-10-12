/**
 * 有文档时的行动卡（T031 / ui-contract §2.5 右栏）。
 *
 * 与 `EmptyOnboarding` 的区别：知识库已有内容时不再需要三步说明，
 * 只保留一个低干扰的"继续补充文档"入口。
 */

import { Button, Text } from '@lobehub/ui/base-ui';
import { theme } from 'antd';
import type { CSSProperties } from 'react';

import { zhCN } from '@/content/zh-CN';

export interface ActionCardProps {
  onUpload: () => void;
}

export default function ActionCard({ onUpload }: ActionCardProps) {
  const { token } = theme.useToken();

  const cardStyle: CSSProperties = {
    display: 'flex',
    flexDirection: 'column',
    gap: 12,
    alignItems: 'flex-start',
    padding: 20,
    border: `1px solid ${token.colorBorderSecondary}`,
    borderRadius: token.borderRadiusLG,
    background: token.colorFillQuaternary,
  };

  return (
    <section aria-label={zhCN.actionCard.title} style={cardStyle}>
      <Text strong>{zhCN.actionCard.title}</Text>
      <Text fontSize={12} type="secondary">
        {zhCN.actionCard.description}
      </Text>
      <Button size="middle" type="fill" onClick={onUpload}>
        {zhCN.actionCard.cta}
      </Button>
    </section>
  );
}
