/**
 * 应用挂载入口（T001 + T005）。
 *
 * Provider 顺序**不可颠倒**（plan D2）：
 *   ConfigProvider(motion) → ThemeProvider(customTheme) → RouterProvider
 * `ThemeProvider` 渲染 antd `App` 并加载字体，依赖上方 `ConfigProvider` 的 context。
 */

import { ConfigProvider, ThemeProvider } from '@lobehub/ui';
import { motion } from 'motion/react';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { RouterProvider } from 'react-router';

import { router } from '@/app/router';
import { ThemeModeProvider, useThemeMode } from '@/app/theme';
import '@/styles/global.css';

function AppProviders() {
  const { resolvedThemeMode, setThemeMode } = useThemeMode();

  return (
    <ConfigProvider motion={motion}>
      <ThemeProvider
        customTheme={{ primaryColor: 'geekblue', neutralColor: 'slate' }}
        themeMode={resolvedThemeMode}
        onThemeModeChange={setThemeMode}
      >
        <RouterProvider router={router} />
      </ThemeProvider>
    </ConfigProvider>
  );
}

const container = document.getElementById('root');

if (!container) {
  throw new Error('Root container #root not found in index.html');
}

createRoot(container).render(
  <StrictMode>
    <ThemeModeProvider>
      <AppProviders />
    </ThemeModeProvider>
  </StrictMode>,
);
