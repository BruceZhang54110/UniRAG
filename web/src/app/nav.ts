/**
 * 侧栏导航配置（T014）。
 * 侧栏项固定为：首页、问答、文档库、任务中心（ui-contract §2.1）。
 *
 * 本文件为 `.ts`：图标以组件引用传入（不是 JSX），因此无需 `.tsx`。
 */

import type { ConsoleNavItem } from '@lobehub/ui/dashboard';
import { FileText, House, ListChecks, MessagesSquare } from 'lucide-react';
import type { ReactNode } from 'react';

import { zhCN } from '@/content/zh-CN';

/** `ConsoleNav` 的图标类型（LucideIcon 可直接传入） */
export type NavIcon = NonNullable<ConsoleNavItem['icon']>;

export interface NavEntry {
  key: string;
  label: string;
  href: string;
  icon: NavIcon;
}

/**
 * 品牌区自定义链接渲染的 props 结构。
 * `@lobehub/ui/dashboard` 未导出 `ConsoleBrandLinkProps`，故在此按结构声明，用于接入路由。
 */
export interface BrandLinkRenderProps {
  'aria-label': string;
  'children': ReactNode;
  'className': string;
  'href': string;
  'onClick': () => void;
}

export const NAV_ITEMS: NavEntry[] = [
  { key: 'home', label: zhCN.nav.home, href: '/', icon: House },
  { key: 'chat', label: zhCN.nav.chat, href: '/chat', icon: MessagesSquare },
  { key: 'documents', label: zhCN.nav.documents, href: '/documents', icon: FileText },
  { key: 'jobs', label: zhCN.nav.jobs, href: '/jobs', icon: ListChecks },
];
