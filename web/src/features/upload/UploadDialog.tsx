/**
 * 最小可用上传对话框（T033 / FR-010，ui-contract §2.8）。
 *
 * 范围纪律（plan D8）：**只有一次性上传**（`POST /api/v1/documents`，≤ 10 MiB），
 * 分块上传与异步任务轮询属于后续的文档库/任务中心特性。
 *
 * 行为契约：
 * - 类型/大小/名称不合规时就地提示，**不发起请求**；
 * - 上传中显示进度（XHR，plan D9）；关闭对话框即中止请求；
 * - 结果按 `INGESTED` / `NOOP` / `PARTIAL` / `FAILED` 给出对应文案；
 * - 收到 202 时提示"已接收，正在后台处理"，**不判为失败、不轮询**（契约 §4 降级约定）。
 */

import { Button, Text, Upload } from '@lobehub/ui/base-ui';
import { Drawer } from '@lobehub/ui/base-ui';
import { theme } from 'antd';
import { useCallback, useEffect, useRef, useState, type CSSProperties } from 'react';
import { Link } from 'react-router';

import { toApiErrorShape } from '@/api/client';
import { uploadDocument, type UploadOutcome, type UploadStatus } from '@/api/documents';
import { zhCN } from '@/content/zh-CN';

/** 扩展名白名单，与后端 `FileTypeValidator` 一致（大小写不敏感） */
export const ALLOWED_EXTENSIONS = ['txt', 'md', 'markdown'] as const;

/** 一次性上传上限 10 MiB（`unirag.upload.one-shot-threshold`） */
export const MAX_UPLOAD_BYTES = 10 * 1024 * 1024;

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
  if (file.size > MAX_UPLOAD_BYTES) {
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

    uploadDocument({
      file,
      docName: docName.trim(),
      title,
      onProgress: setPercent,
      signal: controller.signal,
    })
      .then((result) => {
        abortRef.current = null;
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
            accept=".txt,.md,.markdown"
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
