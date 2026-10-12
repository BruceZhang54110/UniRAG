/**
 * 相对时间（T011 / data-model §1.1）。
 *
 * 规则：< 30 天显示相对时间（刚刚 / N 分钟前 / N 小时前 / N 天前），≥ 30 天显示绝对日期；
 * 归一失败或非数字（`null`）显示"—"。
 */

import { Text } from '@lobehub/ui/base-ui';

import { zhCN } from '@/content/zh-CN';

const MINUTE_MS = 60_000;
const HOUR_MS = 60 * MINUTE_MS;
const DAY_MS = 24 * HOUR_MS;
/** 超过该跨度改用绝对日期 */
const ABSOLUTE_THRESHOLD_MS = 30 * DAY_MS;

function formatAbsoluteDate(value: number): string {
  try {
    return new Intl.DateTimeFormat('zh-CN', {
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
    }).format(new Date(value));
  } catch {
    return zhCN.time.unknown;
  }
}

/** epoch 毫秒 → 可读时间标签；异常值返回"—" */
export function formatRelativeTime(value: number | null | undefined, now: number = Date.now()): string {
  if (typeof value !== 'number' || !Number.isFinite(value)) return zhCN.time.unknown;

  const elapsed = now - value;
  if (elapsed < MINUTE_MS) return zhCN.time.justNow;
  if (elapsed < HOUR_MS) {
    return zhCN.time.minutesAgo.replace('{count}', String(Math.floor(elapsed / MINUTE_MS)));
  }
  if (elapsed < DAY_MS) {
    return zhCN.time.hoursAgo.replace('{count}', String(Math.floor(elapsed / HOUR_MS)));
  }
  if (elapsed < ABSOLUTE_THRESHOLD_MS) {
    return zhCN.time.daysAgo.replace('{count}', String(Math.floor(elapsed / DAY_MS)));
  }
  return formatAbsoluteDate(value);
}

export interface RelativeTimeProps {
  value: number | null | undefined;
  className?: string;
}

export default function RelativeTime({ value, className }: RelativeTimeProps) {
  const isValid = typeof value === 'number' && Number.isFinite(value);

  return (
    <Text
      as="span"
      className={className}
      noWrap
      title={isValid ? formatAbsoluteDate(value) : undefined}
      type="secondary"
    >
      {formatRelativeTime(value)}
    </Text>
  );
}
