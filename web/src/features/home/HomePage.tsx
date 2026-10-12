/**
 * 首页装配（T023 首屏 + T027 知识导航层）。
 *
 * 结构（ui-contract §2）：
 * - 首屏：问候区 + 提问框 + 提交路径（US1）；
 * - 次屏：知识导航层 —— 最近更新列表（US2）+ 行动卡（US3）；
 * - 空库：三步启动引导（US3，替换导航层）；
 * - 最近对话（US4）挂在页面下方。
 *
 * 状态纪律（data-model §3）：`error > empty > processing > ready`；
 * 失败**不得**渲染为"知识库为空"；`loading` 不阻塞提问框。
 */

import { Alert } from '@lobehub/ui/base-ui';
import { useCallback, useMemo, useState, type CSSProperties } from 'react';
import { useNavigate } from 'react-router';

import DocumentDrawer from '@/components/DocumentDrawer';
import ErrorBanner from '@/components/ErrorBanner';
import { zhCN } from '@/content/zh-CN';
import ActionCard from '@/features/home/ActionCard';
import AskBox from '@/features/home/AskBox';
import EmptyOnboarding from '@/features/home/EmptyOnboarding';
import Greeting from '@/features/home/Greeting';
import RecentChats from '@/features/home/RecentChats';
import RecentDocuments from '@/features/home/RecentDocuments';
import SuggestionChips from '@/features/home/SuggestionChips';
import { buildSuggestions } from '@/features/home/state';
import UploadDialog from '@/features/upload/UploadDialog';
import { useHomeOverview } from '@/hooks/useHomeOverview';
import { useRecentChats } from '@/hooks/useRecentChats';

/** 内容区最大宽度（ui-contract §4：≥1280px 时内容区最大 880px 居中） */
const CONTENT_MAX_WIDTH = 880;

export default function HomePage() {
  const { state, overview, items, errorMessage, reload } = useHomeOverview();
  const { items: recentChats, record } = useRecentChats();
  const [openDocName, setOpenDocName] = useState<string | null>(null);
  const [uploadOpen, setUploadOpen] = useState(false);
  const navigate = useNavigate();

  const closeDrawer = useCallback(() => setOpenDocName(null), []);
  const openUpload = useCallback(() => setUploadOpen(true), []);
  const closeUpload = useCallback(() => setUploadOpen(false), []);

  const suggestions = useMemo(() => buildSuggestions(items, overview), [items, overview]);

  /** 建议问题与手动输入走同一条提交路径：记录会话摘要后进入问答上下文 */
  const askQuestion = useCallback(
    (question: string) => {
      record(question);
      navigate(`/chat?q=${encodeURIComponent(question)}`);
    },
    [navigate, record],
  );

  // 仅当数据已就绪时才把文档数交给问候区，否则保持中性表述
  const total = state === 'ready' || state === 'processing' || state === 'empty' ? overview.total : null;

  const showNavigation = state === 'loading' || state === 'processing' || state === 'ready';
  const showSuggestions = state === 'ready' || state === 'processing' || state === 'empty' || state === 'error';

  return (
    <div className="home-content" style={pageStyle}>
      <Greeting state={state} total={total} />
      <AskBox state={state} onSubmit={record} />

      {showSuggestions ? <SuggestionChips suggestions={suggestions} onSelect={askQuestion} /> : null}

      {state === 'error' ? <ErrorBanner message={errorMessage} onRetry={reload} /> : null}

      {state === 'processing' ? (
        <Alert showIcon description={zhCN.overview.processingNotice} type="warning" />
      ) : null}

      {showNavigation ? (
        <div className="home-nav-layer">
          <RecentDocuments items={items} state={state} total={overview.total} onOpen={setOpenDocName} />
          <ActionCard onUpload={openUpload} />
        </div>
      ) : null}

      {/* 空库：三步启动引导（T030/T034），替代导航层；提问框已被禁用并给出原因 */}
      {state === 'empty' ? <EmptyOnboarding onUpload={openUpload} /> : null}

      {/* 最近对话：无历史时组件自身返回 null，不占位（FR-014） */}
      <RecentChats items={recentChats} />

      <DocumentDrawer docName={openDocName} open={openDocName !== null} onClose={closeDrawer} />
      <UploadDialog open={uploadOpen} onClose={closeUpload} onUploaded={reload} />
    </div>
  );
}

const pageStyle: CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 24,
  width: '100%',
  maxWidth: CONTENT_MAX_WIDTH,
  margin: '0 auto',
};
