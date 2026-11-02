/**
 * 对话页视图模型（018-chat-page-layout）：页面与展示组件共用的最小类型集合。
 *
 * 说明：`Citation` 直接复用 `api/retrieval` 的接口形状，不在此重复定义字段。
 */

import type { Citation } from '@/api/retrieval';

export type MessageStatus = 'streaming' | 'done' | 'error';

/** 复制操作的瞬态反馈（不持久化、不进历史） */
export type CopyState = 'idle' | 'copied' | 'failed';

export interface ChatMessage {
  id: string;
  question: string;
  answer: string;
  citations: Citation[];
  degraded: boolean;
  degradeReason: string | null;
  status: MessageStatus;
  /** `status=error` 时的已归一失败文案 */
  errorMessage?: string;
}
