/**
 * 后端错误码 → 用户可见中文文案（plan D14、contracts/backend-api-consumption.md §5）。
 *
 * 纪律：
 * - 界面只展示本表映射后的文案，不透传后端原始 `message`；
 * - `TENANT_*` 一律归一为"服务暂时不可用"，**不得出现"租户"字样**（FR-002 / SC-003）。
 */

import { zhCN } from '@/content/zh-CN';

/** 归一化后的错误形状，由 `api/client.ts` 产出 */
export interface ApiErrorShape {
  code: string;
  message: string;
  status: number;
}

/** 网络层失败（请求未到达服务端）时使用的合成错误码 */
export const NETWORK_ERROR_CODE = 'NETWORK_ERROR';

const CODE_TO_MESSAGE: Record<string, string> = {
  // —— 服务可用性：不暴露租户概念 ——
  TENANT_CODE_REQUIRED: zhCN.errors.serviceUnavailable,
  TENANT_NOT_FOUND: zhCN.errors.serviceUnavailable,
  INTERNAL_ERROR: zhCN.errors.serviceUnavailable,
  [NETWORK_ERROR_CODE]: zhCN.errors.serviceUnavailable,

  // —— 请求内容 ——
  VALIDATION_FAILED: zhCN.errors.validationFailed,

  // —— 上传 ——
  UNSUPPORTED_FILE_TYPE: zhCN.errors.unsupportedFileType,
  FILE_TOO_LARGE: zhCN.errors.fileTooLarge,

  // —— 文档 ——
  DOCUMENT_NOT_FOUND: zhCN.errors.documentNotFound,

  // —— 并发 ——
  CONCURRENT_MODIFICATION: zhCN.errors.conflict,

  // —— 本期不涉及：分块上传与任务轮询（契约 §5），仍给出兜底文案 ——
  UPLOAD_NOT_FOUND: zhCN.errors.uploadSessionInvalid,
  UPLOAD_EXPIRED: zhCN.errors.uploadSessionInvalid,
  UPLOAD_INCOMPLETE: zhCN.errors.uploadSessionInvalid,
  CHECKSUM_MISMATCH: zhCN.errors.uploadSessionInvalid,
  DOCUMENT_INGESTION_JOB_NOT_FOUND: zhCN.errors.jobNotFound,
};

/** HTTP 状态 → 兜底文案（后端未返回可识别 code 时使用） */
function messageForStatus(status: number): string {
  if (status === 404) return zhCN.errors.documentNotFound;
  if (status === 409) return zhCN.errors.conflict;
  if (status >= 500) return zhCN.errors.serviceUnavailable;
  if (status >= 400) return zhCN.errors.validationFailed;
  return zhCN.errors.unknown;
}

/** 按错误码取中文文案；未知码回落 HTTP 状态语义，再回落通用文案 */
export function messageForErrorCode(code: string | null | undefined, status = 0): string {
  if (code) {
    const mapped = CODE_TO_MESSAGE[code];
    if (mapped) return mapped;
  }
  if (status > 0) return messageForStatus(status);
  return zhCN.errors.unknown;
}
