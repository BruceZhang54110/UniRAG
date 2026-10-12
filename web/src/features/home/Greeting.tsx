/**
 * 首页问候区（T020 / FR-001）。
 *
 * - 时段问候：早（5:00–11:59）/ 下午（12:00–17:59）/ 晚（其余时段）；
 * - 副标题随视图状态变化：文档数未知（loading / 未就绪）时使用中性表述，
 *   失败时**不得**说成"知识库为空"（ui-contract §2.7）。
 * - 本期无登录，因此问候语不含任何用户称谓（FR-002：界面不出现账号元素）。
 */

import { Text } from '@lobehub/ui/base-ui';

import { zhCN } from '@/content/zh-CN';
import type { ViewState } from '@/features/home/state';

/** 小时 → 问候语；非法入参回落到中性问候 */
export function greetingForHour(hour: number): string {
  if (!Number.isFinite(hour)) return zhCN.greeting.neutral;
  if (hour >= 5 && hour < 12) return zhCN.greeting.morning;
  if (hour >= 12 && hour < 18) return zhCN.greeting.afternoon;
  return zhCN.greeting.evening;
}

export interface GreetingProps {
  state: ViewState;
  /** 文档总数；未知（数据未返回）时传 `null` */
  total: number | null;
  /** 仅测试使用，便于固定时段 */
  now?: Date;
}

function subtitleFor(state: ViewState, total: number | null): string {
  if (state === 'error') return zhCN.subtitle.error;
  if (state === 'loading' || total === null) return zhCN.subtitle.loading;
  if (state === 'empty') return zhCN.subtitle.empty;
  if (state === 'processing') return zhCN.subtitle.processing;
  return zhCN.subtitle.withCount.replace('{count}', String(total));
}

export default function Greeting({ state, total, now }: GreetingProps) {
  const hour = (now ?? new Date()).getHours();

  return (
    <header>
      <Text as="h1" fontSize={30} weight="bold">
        {greetingForHour(hour)}
      </Text>
      <Text as="p" fontSize={14} type="secondary">
        {subtitleFor(state, total)}
      </Text>
    </header>
  );
}
