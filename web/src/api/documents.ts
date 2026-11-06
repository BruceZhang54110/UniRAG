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
  /** 异步终态失败时的服务端安全文案（022；仅 FAILED 时可能携带） */
  failureMessage?: string;
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
 * 取文档原文（018-chat-page-layout，contracts/document-original.md）。
 *
 * 原文接口要求 `X-Tenant-Code`，因此不能直接放 `<a href>`；由 `apiFetch` 注入租户头后
 * 以 `Blob` 返回，调用方再用对象 URL 打开。错误统一归一为 `ApiError`。
 */
export async function fetchDocumentFile(docName: string, signal?: AbortSignal): Promise<Blob> {
  const response = await apiFetch(`${DOCUMENTS_PATH}/${encodeURIComponent(docName)}/file`, { signal });
  await ensureOk(response);
  return response.blob();
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

/* ------------------------------------------------------------------ */
/* 分块上传 + 异步任务轮询（022 pdf/docx，contracts/upload-formats.md §3/§4） */
/* ------------------------------------------------------------------ */

export const UPLOAD_CHUNK_SIZE = 5 * 1024 * 1024;

const UPLOADS_PATH = '/v1/uploads';

export interface AcceptedUpload {
  kind: 'accepted';
  documentId: number;
  jobId: number;
}

export interface InitUploadSessionParams {
  docName: string;
  title?: string;
  file: File;
  chunkSize?: number;
  signal?: AbortSignal;
}

async function sha256HexOf(buffer: ArrayBuffer): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', buffer);
  return Array.from(new Uint8Array(digest))
    .map((byte) => byte.toString(16).padStart(2, '0'))
    .join('');
}

/** ① 初始化分块会话（声明全文 SHA-256 与原始文件名） */
async function initUploadSession(params: InitUploadSessionParams): Promise<number> {
  const chunkSize = params.chunkSize ?? UPLOAD_CHUNK_SIZE;
  const totalChunks = Math.ceil(params.file.size / chunkSize);
  const contentSha256 = await sha256HexOf(await params.file.arrayBuffer());
  const payload = {
    docName: params.docName,
    title: params.title?.trim() || undefined,
    totalSize: params.file.size,
    chunkSize,
    totalChunks,
    contentSha256,
    fileName: params.file.name,
  };
  const data = await apiRequest<unknown>(UPLOADS_PATH, {
    method: 'POST',
    body: JSON.stringify(payload),
    signal: params.signal,
  });
  return toNumber(asRecord(data).uploadId);
}

/** ② 上传单块（raw body，同 seq 重传 = 幂等覆盖） */
async function uploadChunk(uploadId: number, seq: number, chunk: Blob, signal?: AbortSignal): Promise<void> {
  const response = await apiFetch(`${UPLOADS_PATH}/${uploadId}/chunks/${seq}`, {
    method: 'PUT',
    body: chunk,
    headers: { 'Content-Type': 'application/octet-stream' },
    signal,
  });
  await ensureOk(response);
}

/** ③ 完成会话：异步开启时返回 202 + 任务定位信息，否则同步返回最终入库结果 */
async function completeUploadSession(
  uploadId: number,
  signal?: AbortSignal,
): Promise<AcceptedUpload | { kind: 'completed'; result: UploadResult }> {
  const response = await apiFetch(`${UPLOADS_PATH}/${uploadId}/complete`, { method: 'POST', signal });
  const payload: unknown = await response.json().catch(() => null);
  if (response.status === 202) {
    const data = asRecord(asRecord(payload).data);
    return {
      kind: 'accepted',
      documentId: toNumber(data.documentId),
      jobId: toNumber(data.jobId),
    };
  }
  await ensureOk(response);
  return { kind: 'completed', result: normalizeUploadResult(asRecord(payload).data) };
}

export interface IngestionJobStatus {
  status: string;
  error: { code: string; message: string } | null;
}

function normalizeJobStatus(raw: unknown): IngestionJobStatus {
  const payload = asRecord(raw);
  const error = asRecord(payload.error);
  return {
    status: typeof payload.status === 'string' ? payload.status : 'UNKNOWN',
    error: error.code
      ? { code: String(error.code), message: typeof error.message === 'string' ? error.message : '' }
      : null,
  };
}

/** 任务状态查询（contracts §4） */
export async function getIngestionJobStatus(
  documentId: number,
  jobId: number,
  signal?: AbortSignal,
): Promise<IngestionJobStatus> {
  const data = await apiRequest<unknown>(
    `${DOCUMENTS_PATH}/${documentId}/ingestion-jobs/${jobId}`,
    { signal },
  );
  return normalizeJobStatus(data);
}

const JOB_TERMINAL_STATUSES = new Set(['READY', 'FAILED']);
const JOB_POLL_INTERVAL_MS = 1000;
const JOB_POLL_TIMEOUT_MS = 120_000;

/** 轮询任务至终态：READY / FAILED（或超时/中止则抛 ApiError） */
export async function pollIngestionJob(
  documentId: number,
  jobId: number,
  signal?: AbortSignal,
): Promise<IngestionJobStatus> {
  const deadline = Date.now() + JOB_POLL_TIMEOUT_MS;
  for (;;) {
    const status = await getIngestionJobStatus(documentId, jobId, signal);
    if (JOB_TERMINAL_STATUSES.has(status.status)) return status;
    if (Date.now() >= deadline) {
      throw new ApiError({ code: 'JOB_POLL_TIMEOUT', message: '入库处理超时，请稍后在文档列表查看结果', status: 408 });
    }
    await new Promise((resolve) => setTimeout(resolve, JOB_POLL_INTERVAL_MS));
  }
}

export interface ChunkedUploadParams {
  file: File;
  docName: string;
  title?: string;
  onProgress?: (percent: number) => void;
  signal?: AbortSignal;
}

/**
 * 分块上传编排（> 一次性阈值的大文件）：初始化 → 逐块 → 完成 → （202 时）轮询至终态。
 * 对外语义与 `uploadDocument` 一致：返回最终 `UploadOutcome`，失败抛 `ApiError`。
 */
export async function uploadDocumentChunked(params: ChunkedUploadParams): Promise<UploadOutcome> {
  const uploadId = await initUploadSession({
    docName: params.docName,
    title: params.title,
    file: params.file,
    signal: params.signal,
  });
  const chunkSize = UPLOAD_CHUNK_SIZE;
  const totalChunks = Math.ceil(params.file.size / chunkSize);
  for (let seq = 0; seq < totalChunks; seq++) {
    if (params.signal?.aborted) throw new DOMException('Aborted', 'AbortError');
    const chunk = params.file.slice(seq * chunkSize, Math.min((seq + 1) * chunkSize, params.file.size));
    await uploadChunk(uploadId, seq, chunk, params.signal);
    params.onProgress?.(Math.min(99, Math.round(((seq + 1) / totalChunks) * 100)));
  }
  const outcome = await completeUploadSession(uploadId, params.signal);
  if (outcome.kind === 'completed') {
    params.onProgress?.(100);
    return outcome;
  }
  // 202：轮询任务至终态并归一为 UploadOutcome
  const job = await pollIngestionJob(outcome.documentId, outcome.jobId, params.signal);
  params.onProgress?.(100);
  if (job.status === 'READY') {
    return {
      kind: 'completed',
      result: {
        docName: params.docName,
        title: params.title?.trim() ?? null,
        status: 'INGESTED',
        version: 0,
        chunkCount: 0,
        fileSizeBytes: params.file.size,
        pipelines: [],
      },
    };
  }
  return {
    kind: 'completed',
    result: {
      docName: params.docName,
      title: params.title?.trim() ?? null,
      status: 'FAILED',
      version: 0,
      chunkCount: 0,
      fileSizeBytes: params.file.size,
      pipelines: [],
      failureMessage: job.error?.message ?? undefined,
    },
  };
}
