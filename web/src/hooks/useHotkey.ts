/**
 * 全局快捷键：`/` 或 `Ctrl/Cmd+K` 聚焦提问框（T019 / ui-contract §5）。
 *
 * 纪律：输入状态下不抢占按键 —— 焦点位于 input / textarea / select / contentEditable 时直接放行，
 * 避免用户在表单里打字时被夺走焦点。
 */

import { useEffect, type RefObject } from 'react';

/** 判断事件目标是否为文本输入类元素 */
export function isTypingTarget(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false;
  if (target.isContentEditable) return true;
  const tagName = target.tagName.toLowerCase();
  return tagName === 'input' || tagName === 'textarea' || tagName === 'select';
}

function isFocusShortcut(event: KeyboardEvent): boolean {
  if (event.key === '/') return true;
  return (event.metaKey || event.ctrlKey) && (event.key === 'k' || event.key === 'K');
}

export interface UseHotkeyOptions {
  /** 为 false 时不注册监听（例如尚未挂载输入框） */
  enabled?: boolean;
}

/** 注册全局聚焦快捷键，返回解绑由 React 生命周期托管 */
export function useHotkey(target: RefObject<HTMLElement | null>, options: UseHotkeyOptions = {}): void {
  const enabled = options.enabled ?? true;

  useEffect(() => {
    if (!enabled || typeof window === 'undefined') return;

    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.defaultPrevented || event.repeat) return;
      if (!isFocusShortcut(event)) return;
      if (isTypingTarget(event.target)) return;

      const node = target.current;
      if (!node) return;

      event.preventDefault();
      node.focus();
    };

    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [enabled, target]);
}
