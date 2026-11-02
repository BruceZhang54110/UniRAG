/**
 * 跟随最新内容（018-chat-page-layout，FR-004~006）。
 *
 * 语义：
 * - `following=true`：内容增长时贴到底部（流式增量、发送新问题）；
 * - 用户上翻离开底部 → 暂停跟随并暴露 `following=false`（页面据此显示"回到最新"）；
 * - 用户回到底部附近或点击"回到最新" → 恢复跟随。
 *
 * 贴底用 `requestAnimationFrame` 合并同一帧内的多次增量，避免每个流式分片都触发滚动。
 */

import { useCallback, useEffect, useRef, useState, type RefObject } from 'react';

import { isNearBottom, readScrollMetrics } from './scroll';

export interface StickToBottom {
  containerRef: RefObject<HTMLDivElement | null>;
  /** 当前是否跟随最新内容 */
  following: boolean;
  /** 立即滚到底部；`smooth` 用于"回到最新"按钮 */
  scrollToLatest: (options?: { smooth?: boolean }) => void;
  /** 内容增长后调用（仅 `following` 时生效） */
  followContent: () => void;
}

export function useStickToBottom(): StickToBottom {
  const containerRef = useRef<HTMLDivElement>(null);
  const followingRef = useRef(true);
  const frameRef = useRef<number | null>(null);
  const [following, setFollowingState] = useState(true);

  const setFollowing = useCallback((next: boolean) => {
    if (followingRef.current === next) return;
    followingRef.current = next;
    setFollowingState(next);
  }, []);

  const scrollToLatest = useCallback(
    (options?: { smooth?: boolean }) => {
      const element = containerRef.current;
      if (!element) return;

      const top = element.scrollHeight;
      try {
        element.scrollTo({ top, behavior: options?.smooth ? 'smooth' : 'auto' });
      } catch {
        // 不支持 ScrollToOptions 的环境退化为直接赋值
        element.scrollTop = top;
      }
      setFollowing(true);
    },
    [setFollowing],
  );

  const followContent = useCallback(() => {
    if (!followingRef.current || frameRef.current !== null) return;

    frameRef.current = requestAnimationFrame(() => {
      frameRef.current = null;
      const element = containerRef.current;
      if (!element || !followingRef.current) return;
      element.scrollTop = element.scrollHeight;
    });
  }, []);

  useEffect(() => {
    const element = containerRef.current;
    if (!element) return;

    const handleScroll = () => {
      setFollowing(isNearBottom(readScrollMetrics(element)));
    };

    element.addEventListener('scroll', handleScroll, { passive: true });
    return () => element.removeEventListener('scroll', handleScroll);
  }, [setFollowing]);

  useEffect(
    () => () => {
      if (frameRef.current !== null) cancelAnimationFrame(frameRef.current);
    },
    [],
  );

  return { containerRef, following, scrollToLatest, followContent };
}
