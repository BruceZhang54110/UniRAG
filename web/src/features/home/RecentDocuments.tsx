/**
 * 最近更新文档（T026 / FR-008、FR-009，ui-contract §2.5）。
 *
 * 契约要点：
 * - 最多 6 条（`RECENT_LIMIT`），窄屏由 `styles/global.css` 收为 4 条；
 * - 每条含文件类型图标、标题（超 24 字符截断且 `title` 属性给出全名）、状态标签、相对时间；
 * - 点击条目**不改变路由**，通过 `onOpen` 交由首页打开详情抽屉；
 * - 底部提供进入完整文档列表的入口；
 * - `loading` 用骨架占位，**不阻塞提问框**；`empty` / `error` 由首页负责渲染对应分支，
 *   本组件对这两种状态返回 `null`（失败不得渲染为"知识库为空"）。
 *
 * 实现说明：列表用语义化 `<ul>/<li>` + 可聚焦按钮，而非 `@lobehub/ui` 的 `List`，
 * 原因是需要逐条挂载响应式类名（窄屏隐藏第 5 条起）并保证列表语义与键盘可达
 * （T028 / T043 的硬要求）。
 */

// 深路径导入：包根入口会把整个组件库（含 emoji 数据等）拖入产物与测试进程（T042/T029）
import FileTypeIcon from '@lobehub/ui/es/FileTypeIcon/index';
import Skeleton from '@lobehub/ui/es/Skeleton/index';
import { Text } from '@lobehub/ui/base-ui';
import { theme } from 'antd';
import type { CSSProperties } from 'react';
import { Link } from 'react-router';

import type { DocumentSummary } from '@/api/documents';
import DocumentStatusTag from '@/components/DocumentStatusTag';
import RelativeTime from '@/components/RelativeTime';
import { zhCN } from '@/content/zh-CN';
import { deriveFiletype, displayTitle, type ViewState } from '@/features/home/state';

/** 首页最多展示的最近更新条数（ui-contract §2.5） */
export const RECENT_LIMIT = 6;

/** 标题截断阈值（ui-contract §2.5：超 24 字符单行截断） */
export const TITLE_MAX_LENGTH = 24;

/** 截断标题：超长时保留前 `max` 个字符并追加省略号 */
export function truncateTitle(title: string, max: number = TITLE_MAX_LENGTH): string {
  return title.length > max ? `${title.slice(0, max)}…` : title;
}

export interface RecentDocumentsProps {
  state: ViewState;
  items: DocumentSummary[];
  /** 文档总数，用于标题旁的数量说明 */
  total: number;
  onOpen: (docName: string) => void;
}

export default function RecentDocuments({ state, items, total, onOpen }: RecentDocumentsProps) {
  const { token } = theme.useToken();

  // 空库与失败由首页的分支渲染，本组件不承担
  if (state === 'empty' || state === 'error') return null;

  const loading = state === 'loading';
  const visibleItems = items.slice(0, RECENT_LIMIT);

  const rowStyle: CSSProperties = {
    display: 'flex',
    alignItems: 'center',
    gap: 12,
    width: '100%',
    padding: '10px 12px',
    border: 'none',
    borderRadius: token.borderRadius,
    background: 'transparent',
    textAlign: 'left',
    cursor: 'pointer',
    color: 'inherit',
    font: 'inherit',
  };

  return (
    <div>
      <header style={headerStyle}>
        <Text strong>{zhCN.overview.recentTitle}</Text>
        {loading ? null : (
          <Text fontSize={12} type="secondary">
            {zhCN.overview.recentCount.replace('{count}', String(total))}
          </Text>
        )}
      </header>

      {loading ? (
        <div aria-busy="true" aria-label={zhCN.overview.loadingLabel} data-testid="recent-documents-loading">
          <Skeleton active paragraph={{ rows: 3 }} title={false} />
        </div>
      ) : (
        <ul className="recent-documents-list" style={listStyle}>
          {visibleItems.map((item) => {
            const fullTitle = displayTitle(item);
            return (
              <li key={item.docName} className="recent-doc-item">
                <button
                  className="recent-doc-button"
                  data-testid="recent-doc-item"
                  style={rowStyle}
                  title={fullTitle}
                  type="button"
                  onClick={() => onOpen(item.docName)}
                >
                  <FileTypeIcon filetype={deriveFiletype(item)} size={20} />
                  <Text ellipsis style={{ flex: 1 }} title={fullTitle}>
                    {truncateTitle(fullTitle)}
                  </Text>
                  <DocumentStatusTag status={item.status} />
                  <RelativeTime value={item.createdAt} />
                </button>
              </li>
            );
          })}
        </ul>
      )}

      <div style={footerStyle}>
        <Link className="recent-documents-browse" to="/documents">
          {zhCN.overview.browseAll} →
        </Link>
      </div>
    </div>
  );
}

const headerStyle: CSSProperties = {
  display: 'flex',
  alignItems: 'baseline',
  justifyContent: 'space-between',
  gap: 8,
  marginBottom: 8,
};

const listStyle: CSSProperties = {
  listStyle: 'none',
  margin: 0,
  padding: 0,
};

const footerStyle: CSSProperties = {
  marginTop: 12,
};
