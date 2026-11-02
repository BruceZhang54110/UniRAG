import { describe, expect, it } from 'vitest';

import {
  EARLIER_LOAD_THRESHOLD,
  NEAR_BOTTOM_THRESHOLD,
  anchorCompensation,
  distanceFromBottom,
  isNearBottom,
  readScrollMetrics,
  shouldLoadEarlier,
} from '@/features/chat/scroll';

describe('滚动判定纯函数', () => {
  it('距底距离不为负，内容短于视口时为 0', () => {
    expect(distanceFromBottom({ scrollHeight: 300, scrollTop: 0, clientHeight: 600 })).toBe(0);
    expect(distanceFromBottom({ scrollHeight: 1000, scrollTop: 400, clientHeight: 400 })).toBe(200);
  });

  it('阈值边界：等于阈值仍视为近底', () => {
    const metrics = { scrollHeight: 1000, scrollTop: 500, clientHeight: 500 - NEAR_BOTTOM_THRESHOLD };
    expect(distanceFromBottom(metrics)).toBe(NEAR_BOTTOM_THRESHOLD);
    expect(isNearBottom(metrics)).toBe(true);
    expect(isNearBottom({ ...metrics, scrollTop: 400 })).toBe(false);
  });

  it('自定义阈值生效', () => {
    expect(isNearBottom({ scrollHeight: 1000, scrollTop: 800, clientHeight: 100 }, 200)).toBe(true);
    expect(isNearBottom({ scrollHeight: 1000, scrollTop: 800, clientHeight: 100 }, 99)).toBe(false);
  });

  it('接近顶部才触发更早历史加载', () => {
    expect(shouldLoadEarlier(0)).toBe(true);
    expect(shouldLoadEarlier(EARLIER_LOAD_THRESHOLD)).toBe(true);
    expect(shouldLoadEarlier(EARLIER_LOAD_THRESHOLD + 1)).toBe(false);
    expect(shouldLoadEarlier(Number.NaN)).toBe(false);
  });

  it('前置插入后按高度差补偿', () => {
    expect(anchorCompensation(1000, 1400)).toBe(400);
    expect(anchorCompensation(1000, 1000)).toBe(0);
    expect(anchorCompensation(1000, 800)).toBe(0);
  });

  it('读取指标容错非数值', () => {
    expect(
      readScrollMetrics({
        scrollHeight: Number.NaN,
        scrollTop: Number.POSITIVE_INFINITY,
        clientHeight: 120,
      }),
    ).toEqual({ scrollHeight: 0, scrollTop: 0, clientHeight: 120 });
  });
});

