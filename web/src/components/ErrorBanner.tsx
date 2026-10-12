/**
 * 服务不可用告警条（T012 / FR-012、ui-contract §2.7）。
 *
 * 触发条件：文档列表请求失败（网络错误、5xx、`TENANT_*`）。
 * 展示：`Alert`（error 语义）+ 已归一的中文文案 + 重试按钮。
 * 纪律：请求失败**不得**被渲染为"知识库为空"；文案不得透传后端原始 message。
 */

import { Alert, Button } from '@lobehub/ui/base-ui';

import { zhCN } from '@/content/zh-CN';

export interface ErrorBannerProps {
  /** 已归一的错误文案；缺省时用通用"服务暂时不可用" */
  message?: string | null;
  onRetry?: () => void;
}

export default function ErrorBanner({ message, onRetry }: ErrorBannerProps) {
  return (
    <Alert
      action={
        onRetry ? (
          <Button size="small" type="fill" onClick={onRetry}>
            {zhCN.common.retry}
          </Button>
        ) : undefined
      }
      description={message ?? zhCN.errors.serviceUnavailable}
      showIcon
      title={zhCN.errors.bannerTitle}
      type="error"
    />
  );
}
