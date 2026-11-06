/**
 * 最小可用上传对话框（T033 / FR-010，ui-contract §2.8；022 扩展 pdf/docx 与分块上传）。
 *
 * 行为契约：
 * - 类型/大小/名称不合规时就地提示，**不发起请求**；
 * - ≤ 10 MiB 走一次性上传（XHR 进度）；> 10 MiB 自动切换分块上传（init → 逐块 → complete），
 *   收到 202 后轮询任务至终态（contracts/upload-formats.md §3/§7）；
 * - 上传中显示进度；关闭对话框即中止请求；
 * - 结果按 `INGESTED` / `NOOP` / `PARTIAL` / `FAILED` 给出对应文案。
 */

import { Button, Text, Upload } from '@lobehub/ui/base-ui';
import { Drawer } from '@lobehub/ui/base-ui';
import { theme } from 'antd';
import { useCallback, useEffect, useRef, useState, type CSSProperties } from 'react';
import { Link } from 'react-router';

import { toApiErrorShape } from '@/api/client';
import {
  uploadDocument,
  uploadDocumentChunked,
  type UploadOutcome,
  type UploadStatus,
} from '@/api/documents';
import { zhCN } from '@/content/zh-CN';

/** 扩展名白名单，与后端 `FileTypeValidator` 一致（大小写不敏感，022 含 pdf/docx） */
export const ALLOWED_EXTENSIONS = ['txt', 'md', 'markdown', 'pdf', 'docx'] as const;

/** 文本类单份上限 10 MiB（与后端 `one-shot-threshold` 同值，022 FR-007） */
export const MAX_TEXT_BYTES = 10 * 1024 * 1024;

/** PDF / DOCX 单份上限 50 MiB（与后端 `max-file-size` 默认一致，022 FR-007） */
export const MAX_BINARY_BYTES = 50 * 1024 * 1024;

/** 一次性上传上限 10 MiB（`unirag.upload.one-shot-threshold`）；超过则走分块 */
export const MAX_UPLOAD_BYTES = MAX_TEXT_BYTES;

/** 按扩展名取大小上限（未知扩展名按最严格文本上限处理） */
export function sizeLimitFor(extension: string): number {
  return extension === 'pdf' || extension === 'docx' ? MAX_BINARY_BYTES : MAX_TEXT_BYTES;
}

/** 文件名去扩展名，作为默认文档标识 */
export function defaultDocName(fileName: string): string {
  const dot = fileName.lastIndexOf('.');
  const base = dot > 0 ? fileName.slice(0, dot) : fileName;
  return base.trim();
}

/** 文件校验：返回提示文案，合规时返回 `null` */
export function validateFile(file: File): string | null {
  const extension = file.name.split('.').pop()?.toLowerCase() ?? '';
  if (!ALLOWED_EXTENSIONS.includes(extension as (typeof ALLOWED_EXTENSIONS)[number])) {
    return zhCN.upload.invalidType;
  }
  if (file.size > sizeLimitFor(extension)) {
    return zhCN.upload.fileTooLarge;
  }
  return null;
}

/** 文档标识校验：与后端 `docName` 约束一致（非空、≤255 字符） */
export function validateDocName(value: string): string | null {
  const trimmed = value.trim();
  if (!trimmed) return zhCN.upload.docNameRequired;
  if (trimmed.length > 255) return zhCN.upload.docNameTooLong;
  return null;
}

/** 上传结果文案（data-model §2.2） */
export function uploadResultText(status: UploadStatus): string {
  return zhCN.upload.results[status];
}

type Phase = 'idle' | 'uploading' | 'completed' | 'failed';

export interface UploadDialogProps {
  open: boolean;
  onClose: () => void;
  /** 上传流程结束后通知首页刷新数据（使新文档出现在最近更新中） */
  onUploaded: () => void;
}

export default function UploadDialog({ open, onClose, onUploaded }: UploadDialogProps) {
  const { token } = theme.useToken();
  const [file, setFile] = useState<File | null>(null);
  const [docName, setDocName] = useState('');
  const [title, setTitle] = useState('');
  const [issue, setIssue] = useState<string | null>(null);
  const [phase, setPhase] = useState<Phase>('idle');
  const [percent, setPercent] = useState(0);
  const [outcome, setOutcome] = useState<UploadOutcome | null>(null);
  const [failure, setFailure] = useState<string | null>(null);
  const abortRef = useRef<AbortController | null>(null);

  const reset = useCallback(() => {
    setFile(null);
    setDocName('');
    setTitle('');
    setIssue(null);
    setPhase('idle');
    setPercent(0);
    setOutcome(null);
    setFailure(null);
  }, []);

  const handleClose = useCallback(() => {
    abortRef.current?.abort();
    abortRef.current = null;
    reset();
    onClose();
  }, [onClose, reset]);

  useEffect(() => () => abortRef.current?.abort(), []);

  const selectFile = useCallback(
    (picked: File | undefined) => {
      if (!picked) return;
      setOutcome(null);
      setFailure(null);
      setPercent(0);
      setPhase('idle');
      setFile(picked);
      setDocName((current) => (current.trim() ? current : defaultDocName(picked.name)));
      setIssue(validateFile(picked));
    },
    [],
  );

  const submit = useCallback(() => {
    const fileIssue = file ? validateFile(file) : zhCN.upload.fileRequired;
    const nameIssue = validateDocName(docName);
    const firstIssue = fileIssue ?? nameIssue;
    if (firstIssue) {
      // 前置校验失败：就地提示，不发起请求
      setIssue(firstIssue);
      return;
    }
    if (!file) return;

    const controller = new AbortController();
    abortRef.current = controller;
    setIssue(null);
    setFailure(null);
    setPhase('uploading');
    setPercent(0);

    const run = file.size > MAX_UPLOAD_BYTES
      ? uploadDocumentChunked({
          file,
          docName: docName.trim(),
          title,
          onProgress: setPercent,
          signal: controller.signal,
        })
      : uploadDocument({
          file,
          docName: docName.trim(),
          title,
          onProgress: setPercent,
          signal: controller.signal,
        });

    run
      .then((result) => {
        abortRef.current = null;
        if (result.kind === 'completed' && result.result.status === 'FAILED'
            && result.result.failureMessage) {
          setFailure(result.result.failureMessage);
          setPhase('failed');
          return;
        }
        setOutcome(result);
        setPhase('completed');
        onUploaded();
      })
      .catch((error: unknown) => {
        abortRef.current = null;
        setFailure(toApiErrorShape(error).message);
        setPhase('failed');
      });
  }, [docName, file, onUploaded, title]);

  const uploading = phase === 'uploading';
  const resultText =
    outcome === null
      ? null
      : outcome.kind === 'accepted'
        ? zhCN.upload.results.accepted
        : uploadResultText(outcome.result.status);
  const resultFailed = outcome?.kind === 'completed' && outcome.result.status === 'FAILED';

  return (
    <Drawer open={open} placement="right" title={zhCN.upload.title} width={420} onClose={handleClose}>
      {phase === 'completed' ? (
        <div style={columnStyle}>
          <Text data-testid="upload-result" type={resultFailed ? 'danger' : 'success'}>
            {resultText}
          </Text>
          <div style={actionsStyle}>
            <Link to="/documents" onClick={handleClose}>
              <Button size="middle" type="fill">
                {zhCN.upload.actions.viewDocuments}
              </Button>
            </Link>
            <Button size="middle" onClick={handleClose}>
              {zhCN.upload.actions.close}
            </Button>
          </div>
        </div>
      ) : (
        <div style={columnStyle}>
          <Text type="secondary">{zhCN.upload.description}</Text>

          <Upload
            accept=".txt,.md,.markdown,.pdf,.docx"
            disabled={uploading}
            // 注意：不要传 beforeUpload —— 该组件语义是"返回 false 即把文件过滤掉"（与 antd 不同），
            // 传入会直接吞掉所有文件。类型过滤由 accept 承担，尺寸校验由 selectFile 承担。
            dragger
            onFiles={(files) => selectFile(files[0])}
          >
            <div style={dropZoneStyle(token.colorBorder)}>
              <Text>{file ? file.name : zhCN.upload.pickFile}</Text>
              {file ? (
                <Text fontSize={12} type="secondary">
                  {zhCN.upload.changeFile}
                </Text>
              ) : null}
            </div>
          </Upload>

          <label style={fieldStyle}>
            <Text fontSize={12} type="secondary">
              {zhCN.upload.docNameLabel}
            </Text>
            <input
              aria-label={zhCN.upload.docNameLabel}
              disabled={uploading}
              placeholder={zhCN.upload.docNamePlaceholder}
              style={inputStyle}
              value={docName}
              onChange={(event) => setDocName(event.target.value)}
            />
          </label>

          <label style={fieldStyle}>
            <Text fontSize={12} type="secondary">
              {zhCN.upload.titleLabel}
            </Text>
            <input
              aria-label={zhCN.upload.titleLabel}
              disabled={uploading}
              placeholder={zhCN.upload.titlePlaceholder}
              style={inputStyle}
              value={title}
              onChange={(event) => setTitle(event.target.value)}
            />
          </label>

          {issue ? (
            <Text data-testid="upload-issue" type="danger">
              {issue}
            </Text>
          ) : null}

          {uploading ? (
            <Text type="secondary">
              {zhCN.upload.uploading.replace('{percent}', String(percent))}
            </Text>
          ) : null}

          {phase === 'failed' ? (
            <Text data-testid="upload-failure" type="danger">
              {failure ?? zhCN.errors.unknown}
            </Text>
          ) : null}

          <div style={actionsStyle}>
            <Button disabled={uploading} size="middle" type="primary" onClick={submit}>
              {phase === 'failed' ? zhCN.upload.actions.retry : zhCN.upload.submit}
            </Button>
            <Button disabled={uploading} size="middle" onClick={handleClose}>
              {zhCN.upload.cancel}
            </Button>
          </div>
        </div>
      )}
    </Drawer>
  );
}

const columnStyle: CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 12,
};

const fieldStyle: CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 4,
};

const inputStyle: CSSProperties = {
  width: '100%',
  padding: '6px 10px',
  borderRadius: 6,
  font: 'inherit',
};

const actionsStyle: CSSProperties = {
  display: 'flex',
  gap: 8,
  marginTop: 4,
};

function dropZoneStyle(borderColor: string): CSSProperties {
  return {
    display: 'flex',
    flexDirection: 'column',
    gap: 4,
    alignItems: 'center',
    justifyContent: 'center',
    minHeight: 96,
    padding: 16,
    border: `1px dashed ${borderColor}`,
    borderRadius: 8,
    textAlign: 'center',
  };
}
