/**
 * Vitest 全局环境准备（T003）：jsdom 缺失的浏览器 API 在此补齐。
 */

import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach, vi } from 'vitest';

afterEach(() => {
  cleanup();
});

// jsdom 未实现 matchMedia，主题模块（api 之外）依赖它解析 `auto`
if (typeof window !== 'undefined' && !window.matchMedia) {
  window.matchMedia = ((query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: vi.fn(),
    removeListener: vi.fn(),
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    dispatchEvent: vi.fn(() => false),
  })) as unknown as typeof window.matchMedia;
}

// antd / @lobehub/ui 的部分组件在挂载时探测以下 API
class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}

if (typeof globalThis.ResizeObserver === 'undefined') {
  globalThis.ResizeObserver = ResizeObserverStub as unknown as typeof ResizeObserver;
}

if (typeof globalThis.IntersectionObserver === 'undefined') {
  class IntersectionObserverStub {
    observe() {}
    unobserve() {}
    disconnect() {}
    takeRecords() {
      return [];
    }
  }
  globalThis.IntersectionObserver = IntersectionObserverStub as unknown as typeof IntersectionObserver;
}

// jsdom 的 CSS 命名空间没有 supports()；@lobehub/ui 的全局 focus ring 会调用它。
// 返回 false 会让该特性在测试中整体降级为 no-op（生产环境保持默认开启）。
if (typeof globalThis.CSS === 'undefined' || typeof globalThis.CSS.supports !== 'function') {
  const cssNamespace = (globalThis.CSS ?? {}) as Record<string, unknown>;
  const supports = () => false;

  try {
    Object.defineProperty(cssNamespace, 'supports', {
      configurable: true,
      value: supports,
      writable: true,
    });
    if (typeof globalThis.CSS === 'undefined') {
      Object.defineProperty(globalThis, 'CSS', {
        configurable: true,
        value: cssNamespace,
        writable: true,
      });
    }
  } catch {
    Object.defineProperty(globalThis, 'CSS', {
      configurable: true,
      value: { supports },
      writable: true,
    });
  }
}
