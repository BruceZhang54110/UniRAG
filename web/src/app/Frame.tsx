/**
 * 应用框架（T014）：`ConsoleShell` + `ConsoleNav`。
 *
 * 契约（ui-contract §2.1）：
 * - 侧栏可折叠并记住折叠状态；
 * - 顶栏右侧**只有主题切换**；
 * - **不得**出现租户选择器、租户名、租户编码、登录入口（FR-002 / SC-003）。
 */

import { Icon } from '@lobehub/ui';
import { ConsoleBrand, ConsoleNav, ConsoleShell } from '@lobehub/ui/dashboard';
import { BookOpen } from 'lucide-react';
import { useCallback } from 'react';
import { Link, Outlet, useLocation, useNavigate } from 'react-router';

import { NAV_ITEMS, type BrandLinkRenderProps } from '@/app/nav';
import { ThemeModeSwitch } from '@/app/theme';
import { zhCN } from '@/content/zh-CN';

export default function Frame() {
  const { pathname } = useLocation();
  const navigate = useNavigate();

  const renderBrandLink = useCallback(
    ({ href, children, className, onClick, 'aria-label': ariaLabel }: BrandLinkRenderProps) => (
      <Link aria-label={ariaLabel} className={className} to={href} onClick={onClick}>
        {children}
      </Link>
    ),
    [],
  );

  const handleNavigate = useCallback(
    (href: string) => {
      navigate(href);
    },
    [navigate],
  );

  return (
    <ConsoleShell
      brand={
        <ConsoleBrand
          href="/"
          label={zhCN.brand.name}
          logo={<Icon icon={BookOpen} size={22} />}
          title={zhCN.brand.name}
          renderLink={renderBrandLink}
        />
      }
      navigation={
        <ConsoleNav
          items={NAV_ITEMS}
          label={zhCN.nav.landmark}
          pathname={pathname}
          onNavigate={handleNavigate}
        />
      }
      collapseLabel={zhCN.shell.collapseNav}
      expandLabel={zhCN.shell.expandNav}
      openNavigationLabel={zhCN.shell.openNav}
      skipToContentLabel={zhCN.shell.skipToContent}
      storageKey="unirag.console-shell"
      tools={<ThemeModeSwitch />}
    >
      <Outlet />
    </ConsoleShell>
  );
}
