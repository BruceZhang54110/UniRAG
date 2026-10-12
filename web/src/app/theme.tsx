/**
 * 主题模式状态与受控接线（T005 / plan D2、D12）。
 *
 * `ThemeProvider.themeMode` 默认是 `light` 且不自动跟随系统，"跟随系统"必须由应用实现：
 * 应用持有 `'auto' | 'light' | 'dark'`，`auto` 经 `matchMedia` 解析为具体模式后再交给 ThemeProvider。
 * `ThemeSwitch` 完全受控（无内部 state），因此状态必须由本模块持有。
 */

import { ThemeSwitch } from '@lobehub/ui';
import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';

import { zhCN } from '@/content/zh-CN';

export type ThemeMode = 'auto' | 'light' | 'dark';
export type ResolvedThemeMode = 'light' | 'dark';

/** localStorage 存储键（data-model §4） */
export const THEME_MODE_STORAGE_KEY = 'unirag.theme-mode';

const DARK_QUERY = '(prefers-color-scheme: dark)';
const THEME_MODES: readonly ThemeMode[] = ['auto', 'light', 'dark'];

function isThemeMode(value: unknown): value is ThemeMode {
  return THEME_MODES.includes(value as ThemeMode);
}

function readStoredMode(): ThemeMode {
  if (typeof window === 'undefined') return 'auto';
  try {
    const stored = window.localStorage.getItem(THEME_MODE_STORAGE_KEY);
    return isThemeMode(stored) ? stored : 'auto';
  } catch {
    return 'auto';
  }
}

function writeStoredMode(mode: ThemeMode): void {
  if (typeof window === 'undefined') return;
  try {
    window.localStorage.setItem(THEME_MODE_STORAGE_KEY, mode);
  } catch {
    // 隐私模式不可写：本次会话内仍生效，不阻塞
  }
}

function readSystemPrefersDark(): boolean {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return false;
  return window.matchMedia(DARK_QUERY).matches;
}

/** 解析 `auto` 为具体模式；`light` / `dark` 原样返回 */
export function resolveThemeMode(mode: ThemeMode, prefersDark: boolean): ResolvedThemeMode {
  if (mode === 'auto') return prefersDark ? 'dark' : 'light';
  return mode;
}

export function useSystemPrefersDark(): boolean {
  const [prefersDark, setPrefersDark] = useState(readSystemPrefersDark);

  useEffect(() => {
    if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return;
    const query = window.matchMedia(DARK_QUERY);
    const handleChange = (event: MediaQueryListEvent) => setPrefersDark(event.matches);
    setPrefersDark(query.matches);
    query.addEventListener('change', handleChange);
    return () => query.removeEventListener('change', handleChange);
  }, []);

  return prefersDark;
}

interface ThemeModeContextValue {
  themeMode: ThemeMode;
  resolvedThemeMode: ResolvedThemeMode;
  setThemeMode: (mode: ThemeMode) => void;
}

const ThemeModeContext = createContext<ThemeModeContextValue | null>(null);

export interface ThemeModeProviderProps {
  children: ReactNode;
  /** 仅测试与首屏注入使用；默认读取 localStorage */
  initialMode?: ThemeMode;
}

export function ThemeModeProvider({ children, initialMode }: ThemeModeProviderProps) {
  const [themeMode, setThemeModeState] = useState<ThemeMode>(() => initialMode ?? readStoredMode());
  const prefersDark = useSystemPrefersDark();

  const setThemeMode = useCallback((mode: ThemeMode) => {
    setThemeModeState(isThemeMode(mode) ? mode : 'auto');
    writeStoredMode(mode);
  }, []);

  const value = useMemo<ThemeModeContextValue>(
    () => ({ themeMode, resolvedThemeMode: resolveThemeMode(themeMode, prefersDark), setThemeMode }),
    [themeMode, prefersDark, setThemeMode],
  );

  return <ThemeModeContext.Provider value={value}>{children}</ThemeModeContext.Provider>;
}

export function useThemeMode(): ThemeModeContextValue {
  const context = useContext(ThemeModeContext);
  if (!context) {
    throw new Error('useThemeMode must be used inside ThemeModeProvider');
  }
  return context;
}

/** 顶栏主题切换（受控；`ThemeSwitch` 自身不持有状态） */
export function ThemeModeSwitch() {
  const { themeMode, setThemeMode } = useThemeMode();
  // `ThemeSwitch` 不转发 aria-label（库实现限制了 props 透传），
  // 因此用一个带无障碍名称的分组容器包裹，保证该控件对读屏可辨识（T043）。
  return (
    <span aria-label={zhCN.theme.switchLabel} role="group">
      <ThemeSwitch labels={zhCN.theme.labels} themeMode={themeMode} onThemeSwitch={setThemeMode} />
    </span>
  );
}
