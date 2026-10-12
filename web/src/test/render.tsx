/**
 * 测试渲染助手（T003 测试基础设施的一部分）。
 *
 * 组件在测试中必须与生产一致地处于 Provider 之内：`@lobehub/ui` 的
 * `useMotionComponent` 在没有 `ConfigProvider` 时会直接抛错，base-ui `Button` 就会挂掉。
 *
 * 这里刻意使用**深路径**导入而非包根入口：
 * `@lobehub/ui` 根入口会把整个组件库（含 emoji 数据等）拖进测试进程，
 * 导致 vitest 需要内联转译数千个模块，测试启动从秒级劣化到十几分钟。
 * 深路径由包的 `"./es/*"` exports 正式导出，图中仅含 motion/焦点环等最小依赖。
 *
 * 不引入 `ThemeProvider`：测试不加载 CDN 字体，也不需要自定义主题色；
 * 需要主题 token 的组件（`theme.useToken`）在无 provider 时会拿到默认 token。
 */

import ConfigProvider from '@lobehub/ui/es/ConfigProvider/index';
import { render, type RenderResult } from '@testing-library/react';
import { motion } from 'motion/react';
import type { ReactElement, ReactNode } from 'react';
import { MemoryRouter } from 'react-router';

export interface ProvidersProps {
  children: ReactNode;
  /** 初始路由，默认 `/` */
  initialEntries?: string[];
}

export function AppProviders({ children, initialEntries = ['/'] }: ProvidersProps) {
  return (
    <ConfigProvider motion={motion}>
      <MemoryRouter initialEntries={initialEntries}>{children}</MemoryRouter>
    </ConfigProvider>
  );
}

/** 在应用同款 Provider 上下文内渲染 */
export function renderWithProviders(ui: ReactElement, initialEntries: string[] = ['/']): RenderResult {
  return render(<AppProviders initialEntries={initialEntries}>{ui}</AppProviders>);
}
