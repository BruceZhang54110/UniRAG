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

if (typeof globalThis.IntersectionObserver === 'undefined') {  class IntersectionObserverStub {
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

// jsdom 不实现元素滚动 API；对话页的"滚到底部/定位最新"依赖它们（018 T003）
if (typeof Element !== 'undefined' && typeof Element.prototype.scrollTo !== 'function') {
  Element.prototype.scrollTo = function scrollTo() {} as Element['scrollTo'];
}

if (typeof Element !== 'undefined' && typeof Element.prototype.scrollIntoView !== 'function') {
  Element.prototype.scrollIntoView = function scrollIntoView() {};
}

// jsdom 的 window.scrollTo 会抛 "Not implemented"，测试中统一降级为 no-op
if (typeof window !== 'undefined') {
  window.scrollTo = (() => {}) as typeof window.scrollTo;
}

// jsdom 不实现对象 URL：引用打开原文（018）会用到（真实浏览器由平台提供）
if (typeof URL.createObjectURL !== 'function') {
  URL.createObjectURL = () => 'blob:mock';
}

if (typeof URL.revokeObjectURL !== 'function') {
  URL.revokeObjectURL = () => {};
}
