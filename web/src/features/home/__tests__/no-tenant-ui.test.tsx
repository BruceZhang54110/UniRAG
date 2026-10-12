/**
 * T025：禁止项断言（SC-003 / FR-002，ui-contract §6）。
 *
 * 首页在**任意可达状态**（loading / error / empty / processing / ready）下，
 * 渲染出的 DOM 都不得出现：
 * - 当前租户编码（用与开发配置相同的值做反例常量）；
 * - "租户"/"tenant"字样；
 * - 登录 / 注册 / 账号相关入口。
 *
 * 反例常量说明：`TENANT_FIXTURE` 与 `.env.local` 的 `VITE_DEFAULT_TENANT` 取值一致，
 * 并在断言前写入 `import.meta.env`，以证明"即使部署配置了租户编码，界面也不会泄漏"。
 */

import { screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { zhCN } from '@/content/zh-CN';
import HomePage from '@/features/home/HomePage';
import type { HomeOverviewResult } from '@/hooks/useHomeOverview';
import { emptyOverview, type ViewState } from '@/features/home/state';
import { renderWithProviders } from '@/test/render';

const TENANT_FIXTURE = 'upUS1';
const TENANT_PATTERN = /租户|tenant/i;
const ACCOUNT_PATTERN = /登录|注册|账号|logout|signin|sign-in/i;

vi.mock('@/hooks/useHomeOverview', () => ({
  HOME_DOCUMENT_PAGE_SIZE: 6,
  useHomeOverview: vi.fn(),
}));

const { useHomeOverview } = await import('@/hooks/useHomeOverview');

function mockState(state: ViewState, result: Partial<HomeOverviewResult> = {}) {
  vi.mocked(useHomeOverview).mockReturnValue({
    state,
    overview: emptyOverview(),
    items: [],
    errorMessage: null,
    reload: vi.fn(),
    ...result,
  } as HomeOverviewResult);
}

function expectNoTenantTraces() {
  const text = document.body.textContent ?? '';
  expect(text).not.toMatch(TENANT_PATTERN);
  expect(text).not.toContain(TENANT_FIXTURE);
  expect(text).not.toMatch(ACCOUNT_PATTERN);
}

describe('首页禁止项（SC-003）', () => {
  beforeEach(() => {
    // 反例：即使部署级配置了租户编码，渲染路径也不得读取或展示它
    vi.stubEnv('VITE_DEFAULT_TENANT', TENANT_FIXTURE);
  });

  it('loading 状态不出现租户与账号元素', () => {
    mockState('loading');

    renderWithProviders(<HomePage />);

    expect(screen.getByRole('textbox')).toBeInTheDocument();
    expectNoTenantTraces();
  });

  it('error 状态不出现租户与账号元素', () => {
    mockState('error', { errorMessage: zhCN.errors.serviceUnavailable });

    renderWithProviders(<HomePage />);

    expect(screen.getByText(zhCN.ask.disabledError)).toBeInTheDocument();
    expectNoTenantTraces();
  });

  it('empty 状态不出现租户与账号元素', () => {
    mockState('empty', { overview: emptyOverview() });

    renderWithProviders(<HomePage />);

    expect(screen.getByText(zhCN.ask.disabledEmpty)).toBeInTheDocument();
    expectNoTenantTraces();
  });

  it('processing 状态不出现租户与账号元素', () => {
    mockState('processing', {
      overview: { total: 2, retrievable: 0, processing: 2, hasDocuments: true },
      items: [],
    });

    renderWithProviders(<HomePage />);

    expect(screen.getByText(zhCN.subtitle.processing)).toBeInTheDocument();
    expectNoTenantTraces();
  });

  it('ready 状态不出现租户与账号元素', () => {
    mockState('ready', {
      overview: { total: 1, retrievable: 1, processing: 0, hasDocuments: true },
    });

    renderWithProviders(<HomePage />);

    expect(screen.getByText(zhCN.subtitle.withCount.replace('{count}', '1'))).toBeInTheDocument();
    expectNoTenantTraces();
  });
});
