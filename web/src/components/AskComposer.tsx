/**
 * 共享提问框（018-chat-page-layout T007）：首页提问台与对话页输入区共用。
 *
 * 为什么抽出：输入框容器（焦点环、圆角、边框）在首页与对话页是同一份视觉契约，
 * 按宪法 I"第二个同类用例出现即抽出共享实现"，避免两处样式漂移。
 *
 * 实现约束（沿用首页 AskBox 的既有结论）：只使用具名 `ChatInputAreaInner` +
 * `ChatSendButton` 自建容器，**不使用 `ChatInputArea` 外层组件**——其 `expand`
 * 默认为 `true`，会渲染成全屏浮层。
 *
 * 状态语义区分：
 * - `disabled`：整体不可用（如知识库为空、处理中），输入框与发送位同时停用；
 * - `loading`：生成中，输入框仍可编辑草稿，发送位变为"停止"（018 FR-008）。
 */

import { ChatInputAreaInner, ChatSendButton } from '@lobehub/ui/es/chat/ChatInputArea/index';
import { theme } from 'antd';
import {
  useCallback,
  useMemo,
  useState,
  type CSSProperties,
  type ReactNode,
  type RefObject,
} from 'react';

import { zhCN } from '@/content/zh-CN';

export interface AskComposerProps {
  value: string;
  onChange: (value: string) => void;
  onSend: () => void;
  placeholder: string;
  /** 整体不可用（发送与输入同时停用） */
  disabled?: boolean;
  /** 生成中：发送位显示停止，输入仍可编辑 */
  loading?: boolean;
  onStop?: () => void;
  autoSize?: { minRows: number; maxRows: number };
  fontSize?: number;
  /** `disabled` 时替代发送行的原因说明区 */
  footer?: ReactNode;
  inputRef?: RefObject<HTMLTextAreaElement | null>;
  /** 输入框的无障碍名称 */
  ariaLabel?: string;
  testId?: string;
}

export default function AskComposer({
  value,
  onChange,
  onSend,
  placeholder,
  disabled = false,
  loading = false,
  onStop,
  autoSize = { minRows: 2, maxRows: 6 },
  fontSize,
  footer,
  inputRef,
  ariaLabel,
  testId = 'ask-composer',
}: AskComposerProps) {
  const { token } = theme.useToken();
  const [focused, setFocused] = useState(false);

  const handleChange = useCallback(
    (event: { target: { value: string } }) => onChange(event.target.value),
    [onChange],
  );

  const containerStyle = useMemo<CSSProperties>(
    () => ({
      display: 'flex',
      flexDirection: 'column',
      width: '100%',
      padding: '8px 0',
      border: `1px solid ${focused && !disabled ? token.colorPrimary : token.colorBorder}`,
      borderRadius: token.borderRadiusLG,
      background: token.colorBgContainer,
      boxShadow: focused && !disabled ? `0 0 0 2px ${token.colorPrimaryBg}` : token.boxShadowTertiary,
      transition: 'border-color 120ms ease, box-shadow 120ms ease',
    }),
    [disabled, focused, token],
  );

  return (
    <div data-testid={testId} style={containerStyle}>
      <ChatInputAreaInner
        ref={inputRef}
        aria-label={ariaLabel}
        autoSize={autoSize}
        disabled={disabled}
        placeholder={placeholder}
        style={fontSize ? { fontSize } : undefined}
        value={value}
        onBlur={() => setFocused(false)}
        onChange={handleChange}
        onFocus={() => setFocused(true)}
        onSend={onSend}
      />

      {disabled && footer ? (
        <div style={footerStyle}>{footer}</div>
      ) : (
        <ChatSendButton
          loading={loading}
          onSend={onSend}
          onStop={onStop}
          texts={{ send: zhCN.chat.sendQuestion, warp: zhCN.chat.sendWarp, stop: zhCN.chat.stopAnswer }}
        />
      )}
    </div>
  );
}

const footerStyle: CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'space-between',
  gap: 8,
  padding: '0 16px 4px',
};
