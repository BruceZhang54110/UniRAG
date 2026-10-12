/**
 * 文档接口层（contracts/backend-api-consumption.md §1–§4）。
 *
 * 统一职责：
 * - 把列表/详情的时间字段归一为 epoch 毫秒数字（plan D10）；
 * - 一次性上传走 XHR 以获取进度（plan D9）；
 * - 202 响应走**显式降级分支**：不轮询、不判为失败（契约 §4 降级约定）。
 */

import {
  ApiError,
  apiFetch,
  apiRequest,
  buildApiUrl,
  buildRequestHeaders,
  ensureOk,
  toApiErrorShape,
} from './client';
import { messageForErrorCode } from './errors';

export type DocumentStatus = 'INGESTING' | 'INGESTED' | 'PARTIAL' | 'FAILED' | 'UNKNOWN';

export interface DocumentSummary {
  docName: string;
  title: string | null;
  status: DocumentStatus;
  version: number;
  chunkCount: number;
  fileSizeBytes: number | null;
  mimeType: string | null;
  /** epoch 毫秒；归一失败为 `null`，界面显示"—" */
  createdAt: number | null;
}

export interface DocumentDetail extends DocumentSummary {
  /** epoch 毫秒；归一失败为 `null` */
  updatedAt: number | null;
  /** 后端当前硬编码为 `"upload"`，界面不得宣称其为真实来源 */
  source: string;
}

export interface DocumentListResult {
  total: number;
  items: DocumentSummary[];
}

export type UploadStatus = 'INGESTED' | 'PARTIAL' | 'FAILED' | 'NOOP';

export interface UploadPipelineResult {
  pipelineKey: string;
  status: 'SUCCESS' | 'FAILED';
  chunks: number;
}

export interface UploadResult {
  docName: string;
  title: string | null;
  status: UploadStatus;
  version: number;
  chunkCount: number;
  fileSizeBytes: number | null;
  pipelines: UploadPipelineResult[];
}

/** 上传结果：`completed` 为同步 200 的最终结论；`accepted` 为异步 202 的降级分支 */
export type UploadOutcome = { kind: 'completed'; result: UploadResult } | { kind: 'accepted' };

const DOCUMENTS_PATH = '/v1/documents';
const DOCUMENT_STATUSES: readonly DocumentStatus[] = ['INGESTING', 'INGESTED', 'PARTIAL', 'FAILED'];
const UPLOAD_STATUSES: readonly UploadStatus[] = ['INGESTED', 'PARTIAL', 'FAILED', 'NOOP'];

function asRecord(value: unknown): Record<string, unknown> {
  return typeof value === 'object' && value !== null ? (value as Record<string, unknown>) : {};
}

/** 可解析为有限数字则返回，否则返回该 `NaN`（由调用方决定兜底） */
function toFiniteNumber(value: unknown): number {
  if (typeof value === 'number') return value;
  if (typeof value === 'string' && value.trim() !== '') return Number(value);
  return Number.NaN;
}

function toNumber(value: unknown, fallback = 0): number {
  const parsed = toFiniteNumber(value);
  return Number.isFinite(parsed) ? parsed : fallback;
}

function toNullableNumber(value: unknown): number | null {
  if (value === null || value === undefined) return null;
  const parsed = toFiniteNumber(value);
  return Number.isFinite(parsed) ? parsed : null;
}

function toNullableString(value: unknown): string | null {
  if (typeof value !== 'string') return null;
  const trimmed = value.trim();
  return trimmed === '' ? null : trimmed;
}

/** 时间归一：兼容 epoch 毫秒数字与 ISO-8601 字符串，失败返回 `null`（plan D10） */
export function toEpochMs(value: unknown): number | null {
  if (typeof value === 'number') return Number.isFinite(value) ? value : null;
  if (typeof value === 'string') {
    const trimmed = value.trim();
    if (trimmed === '') return null;
    if (/^-?\d+$/.test(trimmed)) {
      const parsed = Number(trimmed);
      return Number.isFinite(parsed) ? parsed : null;
    }
    const parsed = Date.parse(trimmed);
    return Number.isNaN(parsed) ? null : parsed;
  }
  return null;
}

function normalizeDocumentStatus(value: unknown): DocumentStatus {
  return DOCUMENT_STATUSES.includes(value as DocumentStatus) ? (value as DocumentStatus) : 'UNKNOWN';
}

function normalizeUploadStatus(value: unknown): UploadStatus {
  return UPLOAD_STATUSES.includes(value as UploadStatus) ? (value as UploadStatus) : 'FAILED';
}

function normalizeSummary(raw: unknown): DocumentSummary {
  const item = asRecord(raw);
  return {
    docName: typeof item.docName === 'string' ? item.docName : '',
    title: toNullableString(item.title),
    status: normalizeDocumentStatus(item.status),
    version: toNumber(item.version),
    chunkCount: toNumber(item.chunkCount),
    fileSizeBytes: toNullableNumber(item.fileSizeBytes),
    mimeType: toNullableString(item.mimeType),
    createdAt: toEpochMs(item.createdAt),
  };
}

function normalizeDetail(raw: unknown): DocumentDetail {
  const item = asRecord(raw);
  return {
    ...normalizeSummary(raw),
    updatedAt: toEpochMs(item.updatedAt),
    source: typeof item.source === 'string' ? item.source : '',
  };
}

function normalizeList(raw: unknown): DocumentListResult {
  const payload = asRecord(raw);
  const items = Array.isArray(payload.items) ? payload.items : [];
  return {
    total: toNumber(payload.total),
    items: items.map(normalizeSummary),
  };
}

function normalizeUploadResult(raw: unknown): UploadResult {
  const payload = asRecord(raw);
  const pipelines = Array.isArray(payload.pipelines) ? payload.pipelines : [];
  return {
    docName: typeof payload.docName === 'string' ? payload.docName : '',
    title: toNullableString(payload.title),
    status: normalizeUploadStatus(payload.status),
    version: toNumber(payload.version),
    chunkCount: toNumber(payload.chunkCount),
    fileSizeBytes: toNullableNumber(payload.fileSizeBytes),
    pipelines: pipelines.map((entry) => {
      const pipeline = asRecord(entry);
      return {
        pipelineKey: typeof pipeline.pipelineKey === 'string' ? pipeline.pipelineKey : '',
        status: pipeline.status === 'FAILED' ? 'FAILED' : 'SUCCESS',
        chunks: toNumber(pipeline.chunks),
      };
    }),
  };
}

export interface ListDocumentsParams {
  page?: number;
  /** 首页固定使用 6（契约 §1） */
  size?: number;
  signal?: AbortSignal;
}

/** `GET /api/v1/documents` —— 首页主数据源 */
export async function listDocuments(params: ListDocumentsParams = {}): Promise<DocumentListResult> {
  const page = params.page ?? 1;
  const size = params.size ?? 6;
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  const data = await apiRequest<unknown>(`${DOCUMENTS_PATH}?${query.toString()}`, {
    signal: params.signal,
  });
  return normalizeList(data);
}

/** `GET /api/v1/documents/{docName}` —— 详情抽屉 */
export async function getDocument(docName: string, signal?: AbortSignal): Promise<DocumentDetail> {
  const data = await apiRequest<unknown>(`${DOCUMENTS_PATH}/${encodeURIComponent(docName)}`, { signal });
  return normalizeDetail(data);
}

/**
 * 解析 `Content-Disposition`：后端**仅**返回 RFC 5987 的 `filename*=UTF-8''<pct-encoded>`，
 * 无普通 `filename=` 回退，因此 `decodeURIComponent` 失败时必须兜底（契约 §3）。
 */
export function parseDownloadFilename(header: string | null, fallback: string): string {
  if (!header) return fallback;

  const extended = /filename\*\s*=\s*([^;]+)/i.exec(header);
  if (extended) {
    const rawValue = extended[1].trim().replace(/^["']|["']$/g, '');
    const separator = rawValue.indexOf("''");
    const encoded = separator >= 0 ? rawValue.slice(separator + 2) : rawValue;
    try {
      const decoded = decodeURIComponent(encoded).trim();
      if (decoded) return decoded;
    } catch {
      return fallback;
    }
  }

  const plain = /filename\s*=\s*"([^"]*)"|filename\s*=\s*([^;]+)/i.exec(header);
  if (plain) {
    const value = (plain[1] ?? plain[2] ?? '').trim();
    if (value) return value;
  }
  return fallback;
}

export interface DownloadedDocument {
  blob: Blob;
  filename: string;
}

/** `GET /api/v1/documents/{docName}/file` —— 原始字节，不经 `{data}` 包裹 */
export async function downloadDocument(docName: string, signal?: AbortSignal): Promise<DownloadedDocument> {
  const response = await apiFetch(`${DOCUMENTS_PATH}/${encodeURIComponent(docName)}/file`, { signal });
  await ensureOk(response);
  const blob = await response.blob();
  return {
    blob,
    filename: parseDownloadFilename(response.headers.get('Content-Disposition'), docName),
  };
}

export interface UploadDocumentParams {
  file: File;
  docName: string;
  title?: string;
  onProgress?: (percent: number) => void;
  signal?: AbortSignal;
}

/** `POST /api/v1/documents` —— 一次性上传（multipart，XHR 以获取进度，plan D9） */
export function uploadDocument(params: UploadDocumentParams): Promise<UploadOutcome> {
  return new Promise<UploadOutcome>((resolve, reject) => {
    const form = new FormData();
    form.append('file', params.file);
    form.append('docName', params.docName);
    const title = params.title?.trim();
    if (title) form.append('title', title);

    const xhr = new XMLHttpRequest();
    xhr.open('POST', buildApiUrl(DOCUMENTS_PATH));
    buildRequestHeaders().forEach((value, key) => xhr.setRequestHeader(key, value));

    if (params.onProgress) {
      xhr.upload.onprogress = (event: ProgressEvent) => {
        if (event.lengthComputable && event.total > 0) {
          params.onProgress?.(Math.min(100, Math.round((event.loaded / event.total) * 100)));
        }
      };
    }

    const onAbort = () => xhr.abort();
    if (params.signal?.aborted) {
      reject(new DOMException('Aborted', 'AbortError'));
      return;
    }
    params.signal?.addEventListener('abort', onAbort, { once: true });

    const cleanup = () => params.signal?.removeEventListener('abort', onAbort);

    xhr.onabort = () => {
      cleanup();
      reject(new DOMException('Aborted', 'AbortError'));
    };

    xhr.onerror = () => {
      cleanup();
      reject(new ApiError(toApiErrorShape(new TypeError('network error'))));
    };

    xhr.onload = () => {
      cleanup();
      let payload: unknown = null;
      try {
        payload = JSON.parse(xhr.responseText) as unknown;
      } catch {
        payload = null;
      }

      if (xhr.status === 202) {
        // 显式降级分支：异步模式下不轮询、不判为失败（契约 §4）
        resolve({ kind: 'accepted' });
        return;
      }

      if (xhr.status >= 200 && xhr.status < 300) {
        resolve({ kind: 'completed', result: normalizeUploadResult(asRecord(payload).data) });
        return;
      }

      const code = toNullableString(asRecord(asRecord(payload).error).code);
      reject(
        new ApiError({
          code: code ?? `HTTP_${xhr.status}`,
          message: messageForErrorCode(code, xhr.status),
          status: xhr.status,
        }),
      );
    };

    xhr.send(form);
  });
}
