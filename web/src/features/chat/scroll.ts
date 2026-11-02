/**
 * 滚动判定（018-chat-page-layout，FR-003~006 / FR-045~046）。
 *
 * 只做纯计算，不触碰 DOM：便于在 jsdom（无布局）下直接验证阈值与边界，
 * 真实滚动行为由 `useStickToBottom` 调用这些函数完成。
 */

/** 距底多少像素内仍视为"跟随最新" */
export const NEAR_BOTTOM_THRESHOLD = 48;

/** 距顶多少像素内触发"加载更早历史" */
export const EARLIER_LOAD_THRESHOLD = 80;

export interface ScrollMetrics {
  scrollHeight: number;
  scrollTop: number;
  clientHeight: number;
}

/** 读取元素滚动指标；缺失值按 0 处理，保证服务端渲染/测试环境不抛错 */
export function readScrollMetrics(element: Pick<HTMLElement, 'scrollHeight' | 'scrollTop' | 'clientHeight'>): ScrollMetrics {
  return {
    scrollHeight: Number.isFinite(element.scrollHeight) ? element.scrollHeight : 0,
    scrollTop: Number.isFinite(element.scrollTop) ? element.scrollTop : 0,
    clientHeight: Number.isFinite(element.clientHeight) ? element.clientHeight : 0,
  };
}

/** 距底部距离；内容不足以滚动时为 0 */
export function distanceFromBottom(metrics: ScrollMetrics): number {
  return Math.max(0, metrics.scrollHeight - metrics.scrollTop - metrics.clientHeight);
}

/** 是否处于"跟随最新"区间 */
export function isNearBottom(
  metrics: ScrollMetrics,
  threshold: number = NEAR_BOTTOM_THRESHOLD,
): boolean {
  return distanceFromBottom(metrics) <= threshold;
}

/** 是否应触发更早历史加载（容差用负值兜底，避免滚动位置异常时误触发） */
export function shouldLoadEarlier(
  scrollTop: number,
  threshold: number = EARLIER_LOAD_THRESHOLD,
): boolean {
  return Number.isFinite(scrollTop) && scrollTop <= threshold;
}

/**
 * 前置插入内容后的滚动补偿量：把新增高度加到 `scrollTop` 上，
 * 使用户正在阅读的内容保持在同一视觉位置（FR-046）。
 */
export function anchorCompensation(previousScrollHeight: number, nextScrollHeight: number): number {
  const delta = nextScrollHeight - previousScrollHeight;
  return Number.isFinite(delta) && delta > 0 ? delta : 0;
}
