import { describe, expect, it } from 'vitest';

import type { Citation } from '@/api/retrieval';
import { zhCN } from '@/content/zh-CN';
import { groupCitations } from '@/features/chat/citations';

function citation(overrides: Partial<Citation> = {}): Citation {
  return {
    documentId: 1,
    docName: 'demo-doc',
    pipelineKey: 'main',
    chunkIndex: 0,
    score: 0.9,
    text: '片段正文不应出现在展示项里',
    ...overrides,
  };
}

describe('引用合并', () => {
  it('同一文档的多个片段合并为一条，并保留全部标号', () => {
    const links = groupCitations([
      citation({ documentId: 7, chunkIndex: 0 }),
      citation({ documentId: 9, docName: 'other-doc' }),
      citation({ documentId: 7, chunkIndex: 5 }),
    ]);

    expect(links).toHaveLength(2);
    expect(links[0]).toMatchObject({ key: 'doc-7', name: 'demo-doc', markers: [1, 3] });
    expect(links[1]).toMatchObject({ key: 'doc-9', name: 'other-doc', markers: [2] });
  });

  it('标号升序去重，合并顺序按首次出现', () => {
    const links = groupCitations([
      citation({ documentId: 3, docName: 'A' }),
      citation({ documentId: 4, docName: 'B' }),
      citation({ documentId: 3, docName: 'A' }),
      citation({ documentId: 4, docName: 'B' }),
    ]);

    expect(links.map((link) => link.name)).toEqual(['A', 'B']);
    expect(links[0].markers).toEqual([1, 3]);
    expect(links[1].markers).toEqual([2, 4]);
  });

  it('文档 id 缺失时按名称合并', () => {
    const links = groupCitations([
      citation({ documentId: 0, docName: '同名文档' }),
      citation({ documentId: 0, docName: '同名文档' }),
    ]);

    expect(links).toHaveLength(1);
    expect(links[0].key).toBe('name-同名文档');
    expect(links[0].markers).toEqual([1, 2]);
  });

  it('文档名称缺失时使用中性占位，且不可打开原文', () => {
    const links = groupCitations([citation({ documentId: 0, docName: '   ' })]);

    expect(links[0].name).toBe(zhCN.chat.citationUnknown);
    expect(links[0].docName).toBe('');
  });

  it('空引用返回空数组', () => {
    expect(groupCitations([])).toEqual([]);
  });
});
