/**
 * 租户编码解析（plan D4）。
 *
 * 三层优先级：
 *   1. URL 查询参数 `?tenant=`（管理员通道，读取后写入 sessionStorage）
 *   2. sessionStorage 中的既有值
 *   3. 构建/运行时配置 `VITE_DEFAULT_TENANT`
 *
 * 约束：本模块**只导出取值函数**，不导出任何可渲染内容；
 * 取值结果仅由 `api/client.ts` 注入请求头，永不进入渲染路径（FR-002 / SC-003）。
 */

const TENANT_STORAGE_KEY = 'unirag.tenant';
const TENANT_QUERY_KEY = 'tenant';

function normalize(value: string | null | undefined): string {
  return typeof value === 'string' ? value.trim() : '';
}

function readFromUrl(): string {
  if (typeof window === 'undefined') return '';
  try {
    return normalize(new URLSearchParams(window.location.search).get(TENANT_QUERY_KEY));
  } catch {
    return '';
  }
}

function readFromSession(): string {
  if (typeof window === 'undefined') return '';
  try {
    return normalize(window.sessionStorage.getItem(TENANT_STORAGE_KEY));
  } catch {
    return '';
  }
}

function writeToSession(value: string): void {
  if (typeof window === 'undefined') return;
  try {
    window.sessionStorage.setItem(TENANT_STORAGE_KEY, value);
  } catch {
    // 隐私模式等场景下不可写：本次会话仍可用 URL 值，不做特殊处理
  }
}

/**
 * 解析当前应使用的租户编码；三层都取不到时返回空字符串。
 * 每次调用都会重新读取 URL，便于运维在同一标签页内切换（`?tenant=` 会覆盖并写入会话）。
 */
export function getTenantCode(): string {
  const fromUrl = readFromUrl();
  if (fromUrl) {
    writeToSession(fromUrl);
    return fromUrl;
  }

  const fromSession = readFromSession();
  if (fromSession) return fromSession;

  return normalize(import.meta.env.VITE_DEFAULT_TENANT);
}
