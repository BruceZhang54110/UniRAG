/**
 * 路由表（T015 / plan D6，T022/T023 接入真实页面）。
 *
 * | 路径 | 内容 | 本期状态 |
 * |---|---|---|
 * | `/` | 服务首页（提问台） | 实现（`features/home/HomePage`） |
 * | `/chat` | 问答上下文（`?q=`） | 实现（占位语义，`features/chat/ChatPlaceholderPage`） |
 * | `/documents` | 文档库 | "即将上线"占位 |
 * | `/jobs` | 任务中心 | "即将上线"占位 |
 * | 未知路径 | → 回落首页 | |
 *
 * 文档详情不占路由（首页内 Drawer，见 ui-contract §1）。
 * `/chat` 与两个占位页路由级懒加载，避免进入首屏包（plan D16）。
 */

import { Suspense, lazy } from 'react';
import { Navigate, createBrowserRouter } from 'react-router';

import Frame from '@/app/Frame';
import HomePage from '@/features/home/HomePage';
import { zhCN } from '@/content/zh-CN';

const ChatPlaceholderPage = lazy(() => import('@/features/chat/ChatPlaceholderPage'));
const DocumentsPlaceholder = lazy(() => import('@/features/placeholders/DocumentsPlaceholder'));
const JobsPlaceholder = lazy(() => import('@/features/placeholders/JobsPlaceholder'));

function RouteFallback() {
  return <div aria-busy="true">{zhCN.common.loading}</div>;
}

export const router = createBrowserRouter([
  {
    path: '/',
    element: <Frame />,
    children: [
      { index: true, element: <HomePage /> },
      {
        path: 'chat',
        element: (
          <Suspense fallback={<RouteFallback />}>
            <ChatPlaceholderPage />
          </Suspense>
        ),
      },
      {
        path: 'documents',
        element: (
          <Suspense fallback={<RouteFallback />}>
            <DocumentsPlaceholder />
          </Suspense>
        ),
      },
      {
        path: 'jobs',
        element: (
          <Suspense fallback={<RouteFallback />}>
            <JobsPlaceholder />
          </Suspense>
        ),
      },
      { path: '*', element: <Navigate replace to="/" /> },
    ],
  },
]);
