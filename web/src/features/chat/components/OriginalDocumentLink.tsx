/**
 * 单条引用链接（018-chat-page-layout T016 / contracts/document-original.md）。
 *
 * 原文接口要求 `X-Tenant-Code`，裸 `<a href>` 会因缺租户头而 404，因此流程为：
 * 同步预开空白窗口（规避弹窗拦截）→ 带租户头取 Blob → 赋给新窗口；
 * 预开失败时退化为锚点下载；请求失败则就地提示，不打断对话。
 */

import { theme } from 'antd';
import { useCallback, useState, type CSSProperties } from 'react';

import { fetchDocumentFile } from '@/api/documents';
import { zhCN } from '@/content/zh-CN';

/** 对象 URL 延迟释放：避免新标签页尚未加载完就失效 */
const REVOKE_DELAY_MS = 60_000;

export interface OriginalDocumentLinkProps {
  /** 文档标识（为空表示无法打开原文） */
  docName: string;
  /** 链接文本 */
  name: string;
  /** 该文档在回答中的引用标号 */
  markers: number[];
}

export default function OriginalDocumentLink({ docName, name, markers }: OriginalDocumentLinkProps) {
  const { token } = theme.useToken();
  const [failed, setFailed] = useState(false);
  const [opening, setOpening] = useState(false);

  const open = useCallback(() => {
    if (!docName) {
      setFailed(true);
      return;
    }

    setFailed(false);
    setOpening(true);

    // 必须在 await 之前同步打开，否则会被浏览器判定为非用户手势而拦截
    const popup = typeof window === 'undefined' ? null : window.open('', '_blank');
    if (popup) popup.opener = null;

    fetchDocumentFile(docName)
      .then((blob) => {
        const url = URL.createObjectURL(blob);

        if (popup) {
          popup.location.href = url;
        } else {
          const anchor = document.createElement('a');
          anchor.href = url;
          anchor.download = docName;
          document.body.appendChild(anchor);
          anchor.click();
          anchor.remove();
        }

        window.setTimeout(() => URL.revokeObjectURL(url), REVOKE_DELAY_MS);
      })
      .catch(() => {
        popup?.close();
        setFailed(true);
      })
      .finally(() => setOpening(false));
  }, [docName]);

  const linkStyle: CSSProperties = {
    display: 'flex',
    alignItems: 'center',
    gap: 8,
    width: '100%',
    minWidth: 0,
    padding: 0,
    border: 'none',
    background: 'transparent',
    color: token.colorText,
    font: 'inherit',
    textAlign: 'left',
    cursor: 'pointer',
  };

  return (
    <span style={wrapperStyle}>
      <button
        data-testid="chat-citation-link"
        disabled={opening}
        style={linkStyle}
        type="button"
        onClick={open}
      >
        <span aria-hidden style={{ color: token.colorTextTertiary, flex: 'none', fontSize: 12 }}>
          {markers.map((marker) => `[${marker}]`).join(' ')}
        </span>
        <span
          style={{
            minWidth: 0,
            overflow: 'hidden',
            color: token.colorLink,
            textOverflow: 'ellipsis',
            whiteSpace: 'nowrap',
          }}
        >
          {name}
        </span>
      </button>

      {failed ? (
        <span data-testid="chat-citation-error" role="alert" style={{ color: token.colorError, fontSize: 12 }}>
          {zhCN.chat.citationOpenFailed}
        </span>
      ) : null}
    </span>
  );
}

const wrapperStyle: CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 4,
  minWidth: 0,
  width: '100%',
};
