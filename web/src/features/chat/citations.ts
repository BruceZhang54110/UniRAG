/**
 * 引用合并（018-chat-page-layout，FR-016~018 / FR-023~024）。
 *
 * 回答正文按提示词顺序使用 `[1] [2] …` 标号，因此合并只做"同文档去重"，
 * **不改变标号本身**：一条展示项携带该文档在回答中的全部标号。
 */

import type { Citation } from '@/api/retrieval';
import { zhCN } from '@/content/zh-CN';

export interface CitationLink {
  /** 合并键：优先文档 id，其次文档名称 */
  key: string;
  /** 链接文本：文档标识名，缺失时用中性占位 */
  name: string;
  /** 打开原文用的文档标识（原值；为空表示不可打开） */
  docName: string;
  /** 该文档在回答中的 1 基标号，升序去重 */
  markers: number[];
  /** 首次出现顺序 */
  order: number;
}

/** 按文档合并引用，保留首次出现顺序 */
export function groupCitations(citations: Citation[]): CitationLink[] {
  const byKey = new Map<string, CitationLink>();

  citations.forEach((citation, index) => {
    const docName = citation.docName?.trim() ?? '';
    const documentId = Number.isFinite(citation.documentId) ? citation.documentId : 0;
    const key = documentId > 0 ? `doc-${documentId}` : docName ? `name-${docName}` : `unknown-${index}`;
    const marker = index + 1;

    const existing = byKey.get(key);
    if (existing) {
      if (!existing.markers.includes(marker)) existing.markers.push(marker);
      return;
    }

    byKey.set(key, {
      key,
      name: docName || zhCN.chat.citationUnknown,
      docName,
      markers: [marker],
      order: index,
    });
  });

  return [...byKey.values()]
    .sort((left, right) => left.order - right.order)
    .map((link) => ({ ...link, markers: [...link.markers].sort((left, right) => left - right) }));
}
