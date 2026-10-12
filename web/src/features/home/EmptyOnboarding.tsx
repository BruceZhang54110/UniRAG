/**
 * 空知识库三步启动引导（T030 / FR-010，ui-contract §2.5 空态）。
 *
 * 空库是新产品最常被看到的状态：必须自解释并指向下一步（上传），
 * 否则用户面对一个被禁用的输入框只会认为服务不可用。
 */

import { Button, Steps, Text } from '@lobehub/ui/base-ui';
import { theme } from 'antd';
import type { CSSProperties } from 'react';

import { zhCN } from '@/content/zh-CN';

export interface EmptyOnboardingProps {
  onUpload: () => void;
}

export default function EmptyOnboarding({ onUpload }: EmptyOnboardingProps) {
  const { token } = theme.useToken();

  const cardStyle: CSSProperties = {
    padding: 24,
    border: `1px dashed ${token.colorBorder}`,
    borderRadius: token.borderRadiusLG,
    background: token.colorBgContainer,
  };

  return (
    <section aria-label={zhCN.onboarding.title} style={cardStyle}>
      <Text as="h2" fontSize={16} strong style={{ margin: 0 }}>
        {zhCN.onboarding.title}
      </Text>
      <Text as="p" type="secondary">
        {zhCN.onboarding.description}
      </Text>

      <Steps
        items={zhCN.onboarding.steps.map((step) => ({
          title: step.title,
          description: step.description,
        }))}
        orientation="vertical"
      />

      <Button type="primary" onClick={onUpload}>
        {zhCN.onboarding.cta}
      </Button>
    </section>
  );
}
