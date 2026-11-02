import { fireEvent, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { fetchDocumentFile } from '@/api/documents';
import { zhCN } from '@/content/zh-CN';
import CitationLinks from '@/features/chat/components/CitationLinks';
import type { CitationLink } from '@/features/chat/citations';
import { renderWithProviders } from '@/test/render';

vi.mock('@/api/documents', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/documents')>();
  return { ...actual, fetchDocumentFile: vi.fn() };
});

const fetchDocumentFileMock = vi.mocked(fetchDocumentFile);

const ITEMS: CitationLink[] = [
  { key: 'doc-1', name: '调度运行操作规程', docName: 'opf.md', markers: [1, 3], order: 0 },
  { key: 'doc-2', name: '变压器运行管理规定', docName: 'tr.md', markers: [2], order: 1 },
];

beforeEach(() => {
  fetchDocumentFileMock.mockReset();
});

describe('引用区', () => {
  it('每条只展示标号与文档名称，名称超长不截断语义', () => {
    renderWithProviders(<CitationLinks items={ITEMS} />);

    const links = screen.getAllByTestId('chat-citation-link');
    expect(links).toHaveLength(2);
    expect(links[0].textContent).toContain('[1]');
    expect(links[0].textContent).toContain('[3]');
    expect(links[0].textContent).toContain('调度运行操作规程');
    expect(links[1].textContent).toContain('变压器运行管理规定');
  });

  it('空引用不渲染容器', () => {
    const { container } = renderWithProviders(<CitationLinks items={[]} />);
    expect(container.querySelector('[data-testid="chat-citations"]')).toBeNull();
  });

  it('点击带租户头取回原文；窗口被拦截时退化为下载且不报错', async () => {
    fetchDocumentFileMock.mockResolvedValue(new Blob(['原文'], { type: 'text/markdown' }));
    const openSpy = vi.spyOn(window, 'open').mockReturnValue(null);

    renderWithProviders(<CitationLinks items={ITEMS} />);
    fireEvent.click(screen.getAllByTestId('chat-citation-link')[0]);

    await waitFor(() => expect(fetchDocumentFileMock).toHaveBeenCalledWith('opf.md'));
    expect(openSpy).toHaveBeenCalled();
    expect(screen.queryByTestId('chat-citation-error')).toBeNull();

    openSpy.mockRestore();
  });

  it('原文不可获取时在当前页提示，不静默失败', async () => {
    fetchDocumentFileMock.mockRejectedValue(new Error('404'));
    const popup = { location: { href: '' }, close: vi.fn(), opener: null };
    const openSpy = vi.spyOn(window, 'open').mockReturnValue(popup as unknown as Window);

    renderWithProviders(<CitationLinks items={ITEMS} />);
    fireEvent.click(screen.getAllByTestId('chat-citation-link')[1]);

    await waitFor(() => expect(screen.getByTestId('chat-citation-error')).toBeInTheDocument());
    expect(screen.getByRole('alert').textContent).toContain(zhCN.chat.citationOpenFailed);
    expect(popup.close).toHaveBeenCalled();

    openSpy.mockRestore();
  });
});
