/**
 * T024：提问框行为测试（US1 / FR-003/004/005/006）。
 *
 * 覆盖：
 * - 空输入（含纯空白）按 Enter 不提交、不跳转、保持焦点；
 * - Enter 提交并跳转 `/chat?q=<encodeURIComponent(问题)>`；
 * - Shift + Enter 不提交且**不阻止默认行为**（浏览器据此插入换行）；
 * - `empty` / `error` 状态下输入框禁用，并给出原因文案（不得静默禁用）。
 *
 * 说明：Shift + Enter 的换行是浏览器对 textarea 的原生行为，jsdom 不会真的插入换行，
 * 因此这里断言"未阻止默认行为 + 未提交"这一可观测契约。
 */

import { fireEvent, screen } from '@testing-library/react';
import { useLocation } from 'react-router';
import { describe, expect, it, vi } from 'vitest';

import { zhCN } from '@/content/zh-CN';
import AskBox from '@/features/home/AskBox';
import type { ViewState } from '@/features/home/state';
import { renderWithProviders } from '@/test/render';

function LocationProbe() {
  const location = useLocation();
  return <div data-testid="location">{`${location.pathname}${location.search}`}</div>;
}

function setup(state: ViewState = 'ready') {
  const onSubmit = vi.fn();
  renderWithProviders(
    <>
      <AskBox state={state} onSubmit={onSubmit} />
      <LocationProbe />
    </>,
  );

  return {
    onSubmit,
    textarea: screen.getByRole('textbox') as HTMLTextAreaElement,
    location: () => screen.getByTestId('location').textContent,
  };
}

describe('AskBox', () => {
  it('Enter 提交问题并跳转到 /chat?q=', () => {
    const { textarea, onSubmit, location } = setup('ready');
    const question = '员工手册里有哪些请假规定？';

    fireEvent.change(textarea, { target: { value: question } });
    fireEvent.keyDown(textarea, { key: 'Enter' });

    expect(onSubmit).toHaveBeenCalledWith(question);
    expect(location()).toBe(`/chat?q=${encodeURIComponent(question)}`);
  });

  it('纯空白输入按 Enter 不提交、不跳转，并保持焦点', () => {
    const { textarea, onSubmit, location } = setup('ready');

    fireEvent.change(textarea, { target: { value: '   ' } });
    textarea.blur();
    fireEvent.keyDown(textarea, { key: 'Enter' });

    expect(onSubmit).not.toHaveBeenCalled();
    expect(location()).toBe('/');
    expect(textarea).toHaveFocus();
  });

  it('Shift + Enter 不提交，且不阻止默认行为（浏览器插入换行）', () => {
    const { textarea, onSubmit, location } = setup('ready');

    fireEvent.change(textarea, { target: { value: '第一行' } });
    // fireEvent 返回 false 表示默认行为已被阻止
    const notPrevented = fireEvent.keyDown(textarea, { key: 'Enter', shiftKey: true });

    expect(notPrevented).toBe(true);
    expect(onSubmit).not.toHaveBeenCalled();
    expect(location()).toBe('/');
  });

  it('empty 状态下输入框禁用并给出原因文案', () => {
    const { textarea, location } = setup('empty');

    expect(textarea).toBeDisabled();
    expect(screen.getByText(zhCN.ask.disabledEmpty)).toBeInTheDocument();

    fireEvent.change(textarea, { target: { value: '任意问题' } });
    fireEvent.keyDown(textarea, { key: 'Enter' });
    expect(location()).toBe('/');
  });

  it('error 状态下输入框禁用，且原因与 empty 不同（失败不得说成空库）', () => {
    const { textarea, location } = setup('error');

    expect(textarea).toBeDisabled();
    expect(screen.getByText(zhCN.ask.disabledError)).toBeInTheDocument();
    expect(screen.queryByText(zhCN.ask.disabledEmpty)).not.toBeInTheDocument();

    fireEvent.keyDown(textarea, { key: 'Enter' });
    expect(location()).toBe('/');
  });

  it('loading 状态下不禁用（数据未返回不阻塞提问）', () => {
    const { textarea } = setup('loading');

    expect(textarea).not.toBeDisabled();
  });

  /**
   * 回归护栏（2026-10-12 修复）：提问框必须使用具名 `ChatInputAreaInner`。
   * 该模块默认导出是外层 `ChatInputArea`（`expand` 默认 true），会渲染 `styles.fullscreen`
   * 全屏浮层（内联 `inset-block-start` + 不透明背景 + z-index）盖住整页 —— 首页因此变空白。
   * jsdom 无布局，断言以"不得出现全屏浮层的结构特征"为准：textarea 之外不得有带
   * `inset-block-start` 内联样式的祖先容器。
   */
  it('不得渲染全屏浮层（防止误用外层 ChatInputArea）', () => {
    const { textarea } = setup('ready');

    let node: HTMLElement | null = textarea.parentElement;
    while (node) {
      expect(node.getAttribute('style') ?? '').not.toContain('inset-block-start');
      node = node.parentElement;
    }
  });
});
