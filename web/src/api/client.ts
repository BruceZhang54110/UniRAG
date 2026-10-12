/**
 * 请求封装（plan D9）：baseURL、`X-Tenant-Code` 注入、`{data}` 解包、错误归一。
 *
 * 约定（contracts/backend-api-consumption.md §0）：
 * - 成功响应除文件下载外一律为 `{ "data": ... }`；
 * - 失败响应为 `{ "error": { "code", "message" } }`；
 * - 所有 `/api/**` 请求必须携带 `X-Tenant-Code`，取值仅来自 `api/tenant.ts`。
 */

import { NETWORK_ERROR_CODE, messageForErrorCode, type ApiErrorShape } from './errors';
import { getTenantCode } from './tenant';

const DEFAULT_API_BASE = '/api';

/** 归一化后的接口错误：`code` 为后端错误码（或合成的网络错误码），`message` 为中文文案 */
export class ApiError extends Error implements ApiErrorShape {
  readonly code: string;
  readonly status: number;

  constructor(shape: ApiErrorShape) {
    super(shape.message);
    this.name = 'ApiError';
    this.code = shape.code;
    this.status = shape.status;
  }

  toShape(): ApiErrorShape {
    return { code: this.code, message: this.message, status: this.status };
  }
}

export interface RequestOptions {
  method?: string;
  body?: BodyInit | null;
  headers?: HeadersInit;
  signal?: AbortSignal;
}

/** 计算 API 基地址：开发期留空即走 Vite 代理的 `/api` */
export function getApiBase(): string {
  const configured = (import.meta.env.VITE_API_BASE ?? '').trim();
  const base = configured || DEFAULT_API_BASE;
  return base.replace(/\/+$/, '');
}

/** 拼接完整请求地址（同时供 XHR 上传复用） */
export function buildApiUrl(path: string): string {
  const suffix = path.startsWith('/') ? path : `/${path}`;
  return `${getApiBase()}${suffix}`;
}

/**
 * 构造请求头：注入租户头。租户取值是本模块对 `api/tenant.ts` 的唯一消费点，
 * 其他模块（含 XHR 上传）必须经此函数取头部，避免租户逻辑扩散。
 */
export function buildRequestHeaders(extra?: HeadersInit): Headers {
  const headers = new Headers(extra);
  const tenant = getTenantCode();
  if (tenant) headers.set('X-Tenant-Code', tenant);
  return headers;
}

export function isAbortError(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError';
}

function networkError(): ApiError {
  return new ApiError({
    code: NETWORK_ERROR_CODE,
    message: messageForErrorCode(NETWORK_ERROR_CODE),
    status: 0,
  });
}

async function parseJsonBody(response: Response): Promise<unknown> {
  const text = await response.text().catch(() => '');
  if (!text) return null;
  try {
    return JSON.parse(text) as unknown;
  } catch {
    return null;
  }
}

function readErrorCode(payload: unknown): string | null {
  if (typeof payload !== 'object' || payload === null) return null;
  const error = (payload as { error?: unknown }).error;
  if (typeof error !== 'object' || error === null) return null;
  const code = (error as { code?: unknown }).code;
  return typeof code === 'string' && code ? code : null;
}

/** 把任意响应归一为 `ApiError` 并抛出；响应为 2xx 时原样返回 */
export async function ensureOk(response: Response): Promise<Response> {
  if (response.ok) return response;
  const payload = await parseJsonBody(response);
  const code = readErrorCode(payload);
  throw new ApiError({
    code: code ?? `HTTP_${response.status}`,
    message: messageForErrorCode(code, response.status),
    status: response.status,
  });
}

/** 原始请求：返回未解包的 `Response`（文件下载等非 `{data}` 场景） */
export async function apiFetch(path: string, options: RequestOptions = {}): Promise<Response> {
  const headers = buildRequestHeaders(options.headers);
  const isFormData = typeof FormData !== 'undefined' && options.body instanceof FormData;
  if (options.body != null && !isFormData && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json');
  }

  try {
    return await fetch(buildApiUrl(path), {
      method: options.method ?? 'GET',
      body: options.body ?? null,
      headers,
      signal: options.signal,
    });
  } catch (error) {
    if (isAbortError(error)) throw error;
    throw networkError();
  }
}

/** 标准请求：解包 `{ data }` 并归一错误 */
export async function apiRequest<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const response = await apiFetch(path, options);
  await ensureOk(response);
  const payload = await parseJsonBody(response);
  if (payload === null) return null as T;
  if (typeof payload === 'object' && 'data' in payload) {
    return (payload as { data: T }).data;
  }
  return payload as T;
}

/** 把未知异常归一为 `ApiErrorShape`，供 Hook 与组件统一消费 */
export function toApiErrorShape(error: unknown): ApiErrorShape {
  if (error instanceof ApiError) return error.toShape();
  return {
    code: NETWORK_ERROR_CODE,
    message: messageForErrorCode(NETWORK_ERROR_CODE),
    status: 0,
  };
}
