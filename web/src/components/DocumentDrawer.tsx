/**
 * 文档详情抽屉（T013 / data-model §1.2、ui-contract §2.5）。
 *
 * - 首页内查看详情，**不改变路由**；
 * - `source` 按原值展示，并注明它由服务端固定返回、不代表真实上传渠道；
 * - 详情请求失败（`DOCUMENT_NOT_FOUND`）在抽屉内提示，不影响首页其余内容。
 */

import { Drawer, Descriptions, Spin, Text, type DescriptionsItem } from '@lobehub/ui/base-ui';
import { useEffect, useState } from 'react';

import { isAbortError, toApiErrorShape } from '@/api/client';
import { getDocument, type DocumentDetail } from '@/api/documents';
import DocumentStatusTag from '@/components/DocumentStatusTag';
import RelativeTime from '@/components/RelativeTime';
import { zhCN } from '@/content/zh-CN';

export interface DocumentDrawerProps {
  /** 为 `null` 时不请求、不渲染内容 */
  docName: string | null;
  open: boolean;
  onClose: () => void;
}

type DrawerState = 'idle' | 'loading' | 'ready' | 'error';

/** 字节数 → 可读体积；入参为 null/异常时显示"—" */
export function formatFileSize(bytes: number | null | undefined): string {
  if (typeof bytes !== 'number' || !Number.isFinite(bytes) || bytes < 0) return zhCN.document.unknown;
  if (bytes < 1024) return `${bytes} B`;
  const kilobytes = bytes / 1024;
  if (kilobytes < 1024) return `${kilobytes.toFixed(1)} KB`;
  return `${(kilobytes / 1024).toFixed(1)} MB`;
}

function buildItems(detail: DocumentDetail): DescriptionsItem[] {
  const unknown = zhCN.document.unknown;
  const fields = zhCN.document.fields;

  return [
    { key: 'docName', label: fields.docName, children: detail.docName || unknown },
    { key: 'title', label: fields.title, children: detail.title ?? unknown },
    { key: 'status', label: fields.status, children: <DocumentStatusTag status={detail.status} /> },
    { key: 'version', label: fields.version, children: `v${detail.version}` },
    { key: 'chunkCount', label: fields.chunkCount, children: String(detail.chunkCount) },
    { key: 'fileSize', label: fields.fileSize, children: formatFileSize(detail.fileSizeBytes) },
    { key: 'mimeType', label: fields.mimeType, children: detail.mimeType ?? unknown },
    {
      key: 'createdAt',
      label: fields.createdAt,
      children: <RelativeTime value={detail.createdAt} />,
    },
    {
      key: 'updatedAt',
      label: fields.updatedAt,
      children: <RelativeTime value={detail.updatedAt} />,
    },
    { key: 'source', label: fields.source, children: detail.source || unknown },
  ];
}

export default function DocumentDrawer({ docName, open, onClose }: DocumentDrawerProps) {
  const [state, setState] = useState<DrawerState>('idle');
  const [detail, setDetail] = useState<DocumentDetail | null>(null);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  useEffect(() => {
    if (!open || !docName) {
      setState('idle');
      setDetail(null);
      setErrorMessage(null);
      return;
    }

    const controller = new AbortController();
    setState('loading');
    setErrorMessage(null);

    getDocument(docName, controller.signal)
      .then((result) => {
        setDetail(result);
        setState('ready');
      })
      .catch((error: unknown) => {
        if (isAbortError(error)) return;
        setDetail(null);
        setErrorMessage(toApiErrorShape(error).message);
        setState('error');
      });

    return () => controller.abort();
  }, [docName, open]);

  return (
    <Drawer open={open} placement="right" title={zhCN.document.title} width={420} onClose={onClose}>
      {state === 'loading' ? <Spin /> : null}

      {state === 'error' ? (
        <Text type="danger">{errorMessage ?? zhCN.document.notFound}</Text>
      ) : null}

      {state === 'ready' && detail ? (
        <div>
          <Descriptions column={1} items={buildItems(detail)} />
          <Text as="p" fontSize={12} type="secondary">
            {zhCN.document.sourceNote}
          </Text>
        </div>
      ) : null}
    </Drawer>
  );
}
