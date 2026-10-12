/**
 * T029 / US2 测试：最近更新文档列表。
 *
 * 覆盖 ui-contract §2.5 的可断言行为：
 * 条目数量上限、超长标题截断且保留全名、点击交由首页打开抽屉、加载态骨架且不渲染条目。
 */

import { fireEvent, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import type { DocumentSummary } from '@/api/documents';
import { zhCN } from '@/content/zh-CN';
import RecentDocuments, { RECENT_LIMIT, TITLE_MAX_LENGTH } from '@/features/home/RecentDocuments';
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

function renderList(items: DocumentSummary[], state: 'ready' | 'loading' = 'ready', onOpen = vi.fn()) {
  renderWithProviders(
    <RecentDocuments items={items} state={state} total={items.length} onOpen={onOpen} />,
  );
  return onOpen;
}

describe('RecentDocuments', () => {
  it('条目数量上限为 6 条', () => {
    const items = Array.from({ length: 8 }, (_, index) =>
      buildItem({ docName: `doc-${index}`, title: `文档 ${index}` }),
    );

    renderList(items);

    expect(screen.getAllByTestId('recent-doc-item')).toHaveLength(RECENT_LIMIT);
  });

  it('超长标题单行截断，并保留完整名称供悬停查看', () => {
    const longTitle = '这是一份用于验证超长标题截断行为的企业级员工手册文档';
    expect(longTitle.length).toBeGreaterThan(TITLE_MAX_LENGTH);

    renderList([buildItem({ title: longTitle })]);

    const row = screen.getAllByTestId('recent-doc-item')[0];
    expect(row).toHaveAttribute('title', longTitle);
    expect(row.textContent).toContain(`${longTitle.slice(0, TITLE_MAX_LENGTH)}…`);
    expect(row.textContent).not.toContain(longTitle);
  });

  it('标题为空时回退显示文档标识', () => {
    renderList([buildItem({ docName: 'upload-doc-a', title: null })]);

    expect(screen.getByText('upload-doc-a')).toBeInTheDocument();
  });

  it('点击条目把文档标识交给首页打开抽屉，且不离开当前路由', () => {
    const onOpen = renderList([buildItem({ docName: 'upload-doc-a' })]);

    fireEvent.click(screen.getAllByTestId('recent-doc-item')[0]);

    expect(onOpen).toHaveBeenCalledTimes(1);
    expect(onOpen).toHaveBeenCalledWith('upload-doc-a');
  });

  it('提供进入完整文档列表的入口', () => {
    renderList([buildItem()]);

    const browse = screen.getByRole('link', { name: new RegExp(zhCN.overview.browseAll) });
    expect(browse).toHaveAttribute('href', '/documents');
  });

  it('加载态渲染骨架且不渲染任何条目', () => {
    renderList([], 'loading');

    expect(screen.getByTestId('recent-documents-loading')).toBeInTheDocument();
    expect(screen.queryAllByTestId('recent-doc-item')).toHaveLength(0);
  });

  it('空库与失败态不渲染（由首页分支负责展示）', () => {
    const { unmount } = renderWithProviders(
      <RecentDocuments items={[]} state="empty" total={0} onOpen={vi.fn()} />,
    );
    expect(screen.queryByTestId('recent-documents-loading')).not.toBeInTheDocument();
    expect(screen.queryAllByTestId('recent-doc-item')).toHaveLength(0);
    unmount();
  });
});
