/**
 * T040 / US4 测试：建议问题与最近对话。
 *
 * 覆盖：
 * - 建议问题生成规则（2 条来自可检索文档标题 + 预置补齐到 4 条）与禁用规则；
 * - `RecentChats` 无历史时不渲染任何容器（FR-014）；
 * - localStorage 脏数据被清理（data-model §1.5）。
 */

import { fireEvent, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import type { DocumentSummary } from '@/api/documents';
import { zhCN } from '@/content/zh-CN';
import RecentChats from '@/features/home/RecentChats';
import SuggestionChips from '@/features/home/SuggestionChips';
import { buildSuggestions, deriveOverview, SUGGESTION_COUNT } from '@/features/home/state';
import {
  RECENT_CHATS_KEY,
  RECENT_CHAT_LIMIT,
  appendSession,
  parseRecentChats,
  summarizeQuestion,
  type ChatSessionSummary,
} from '@/hooks/useRecentChats';
import { renderWithProviders } from '@/test/render';

function buildItem(overrides: Partial<DocumentSummary> = {}): DocumentSummary {
  return {
    docName: 'doc-a',
    title: '员工手册',
    status: 'INGESTED',
    version: 1,
    chunkCount: 12,
    fileSizeBytes: 2048,
    mimeType: 'text/markdown',
    createdAt: Date.now() - 60_000,
    ...overrides,
  };
}

function buildSession(overrides: Partial<ChatSessionSummary> = {}): ChatSessionSummary {
  return {
    id: 'session-1',
    question: '员工手册里关于年假的规定是什么？',
    lastActiveAt: Date.now() - 60_000,
    messageCount: 1,
    ...overrides,
  };
}

afterEach(() => {
  window.localStorage.clear();
});

describe('建议问题生成规则', () => {
  it('有 2 篇可检索文档时：2 条来自文档标题 + 2 条预置，共 4 条', () => {
    const items = [
      buildItem({ docName: 'a', title: '员工手册' }),
      buildItem({ docName: 'b', title: '部署手册' }),
    ];

    const suggestions = buildSuggestions(items, deriveOverview(items, 2));

    expect(suggestions).toHaveLength(SUGGESTION_COUNT);
    expect(suggestions.filter((item) => item.source === 'document')).toHaveLength(2);
    expect(suggestions[0].text).toContain('员工手册');
    expect(suggestions[1].text).toContain('部署手册');
    expect(suggestions.every((item) => item.enabled)).toBe(true);
  });

  it('不足 2 篇可检索文档时用预置问题补齐到 4 条且不重复', () => {
    const items = [buildItem({ docName: 'a', title: '员工手册' })];

    const suggestions = buildSuggestions(items, deriveOverview(items, 1));

    expect(suggestions).toHaveLength(SUGGESTION_COUNT);
    expect(new Set(suggestions.map((item) => item.text)).size).toBe(SUGGESTION_COUNT);
  });

  it('没有可检索文档时全部置为不可用', () => {
    const items = [buildItem({ status: 'INGESTING' })];

    const suggestions = buildSuggestions(items, deriveOverview(items, 1));

    expect(suggestions.every((item) => item.enabled === false)).toBe(true);
  });
});

describe('SuggestionChips', () => {
  it('可用时点击回传问题文本', () => {
    const onSelect = vi.fn();
    const suggestions = buildSuggestions([buildItem()], deriveOverview([buildItem()], 1));
    renderWithProviders(<SuggestionChips suggestions={suggestions} onSelect={onSelect} />);

    const chips = screen.getAllByTestId('suggestion-chip');
    expect(chips).toHaveLength(SUGGESTION_COUNT);

    fireEvent.click(chips[0]);
    expect(onSelect).toHaveBeenCalledWith(suggestions[0].text);
  });

  it('不可用时不提交，并给出原因', () => {
    const onSelect = vi.fn();
    const items = [buildItem({ status: 'INGESTING' })];
    const suggestions = buildSuggestions(items, deriveOverview(items, 1));
    renderWithProviders(<SuggestionChips suggestions={suggestions} onSelect={onSelect} />);

    fireEvent.click(screen.getAllByTestId('suggestion-chip')[0]);

    expect(onSelect).not.toHaveBeenCalled();
    expect(screen.getByTestId('suggestion-disabled-hint')).toHaveTextContent(zhCN.suggestions.disabled);
  });
});

describe('RecentChats', () => {
  it('无历史时不渲染任何容器', () => {
    const { container } = renderWithProviders(<RecentChats items={[]} />);

    expect(screen.queryByTestId('recent-chats')).not.toBeInTheDocument();
    expect(container.textContent).toBe('');
  });

  it('有历史时渲染条目并带上原问题链接', () => {
    renderWithProviders(<RecentChats items={[buildSession()]} />);

    expect(screen.getByTestId('recent-chats')).toBeInTheDocument();
    const link = screen.getByRole('link', { name: /员工手册/ });
    expect(link.getAttribute('href')).toContain('/chat?q=');
  });

  it('最多展示 6 条', () => {
    const items = Array.from({ length: 9 }, (_, index) =>
      buildSession({ id: `s-${index}`, question: `问题 ${index}` }),
    );
    renderWithProviders(<RecentChats items={items.slice(0, RECENT_CHAT_LIMIT)} />);

    expect(screen.getAllByTestId('recent-chat-item')).toHaveLength(RECENT_CHAT_LIMIT);
  });
});

describe('最近对话存储', () => {
  it('问题摘要截断到 80 字符', () => {
    const long = '很长的问'.repeat(30);
    const summary = summarizeQuestion(long);

    expect(summary.length).toBe(81);
    expect(summary.endsWith('…')).toBe(true);
  });

  it('追加会话保持倒序与上限', () => {
    let list: ChatSessionSummary[] = [];
    for (let index = 0; index < 8; index += 1) {
      list = appendSession(list, `问题 ${index}`, 1_000 + index, `id-${index}`);
    }

    expect(list).toHaveLength(RECENT_CHAT_LIMIT);
    expect(list[0].id).toBe('id-7');
    expect(list[0].lastActiveAt).toBeGreaterThan(list[1].lastActiveAt);
  });

  it('脏数据被解析为空并清理存储键', () => {
    expect(parseRecentChats('not-json')).toEqual([]);
    expect(parseRecentChats('{"a":1}')).toEqual([]);
    expect(parseRecentChats('[{"id":"x"}]')).toEqual([]);

    window.localStorage.setItem(RECENT_CHATS_KEY, 'not-json');
    expect(parseRecentChats(window.localStorage.getItem(RECENT_CHATS_KEY))).toEqual([]);
  });
});
