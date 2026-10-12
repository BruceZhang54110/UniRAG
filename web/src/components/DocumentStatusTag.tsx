/**
 * 文档状态标签（T010 / data-model §2.1）。
 *
 * | 后端 status | 文案 | 颜色 |
 * |---|---|---|
 * | INGESTED | 可检索 | 绿 success |
 * | INGESTING | 处理中 | 蓝 info |
 * | PARTIAL | 部分成功 | 橙 warning |
 * | FAILED | 处理失败 | 红 error |
 *
 * 语义纪律：`INGESTED` 表示该文档版本已进入索引；`NOOP` 只出现在上传结果中，不用于列表状态。
 * 状态信息不只靠颜色传达：标签内同时有文字（无障碍要求，ui-contract §5）。
 */

import { Tag, type TagProps } from '@lobehub/ui/base-ui';

import type { DocumentStatus } from '@/api/documents';
import { zhCN } from '@/content/zh-CN';

type StatusColor = NonNullable<TagProps['color']>;

interface StatusStyle {
  color: StatusColor;
  label: string;
}

const STATUS_STYLES: Record<DocumentStatus, StatusStyle> = {
  INGESTED: { color: 'success', label: zhCN.documentStatus.INGESTED },
  INGESTING: { color: 'info', label: zhCN.documentStatus.INGESTING },
  PARTIAL: { color: 'warning', label: zhCN.documentStatus.PARTIAL },
  FAILED: { color: 'error', label: zhCN.documentStatus.FAILED },
  UNKNOWN: { color: 'default', label: zhCN.documentStatus.unknown },
};

export interface DocumentStatusTagProps {
  status: DocumentStatus;
}

export default function DocumentStatusTag({ status }: DocumentStatusTagProps) {
  const { color, label } = STATUS_STYLES[status] ?? STATUS_STYLES.UNKNOWN;

  return (
    <Tag color={color} size="small">
      {label}
    </Tag>
  );
}
