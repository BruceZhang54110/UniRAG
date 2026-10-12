/**
 * 首页提问框（T021 / FR-003–FR-006，ui-contract §2.2）。
 *
 * 实现约束（plan D11）：使用 `ChatInputArea.Inner`（受控 TextArea）+ `ChatSendButton` 自建容器，
 * **不使用 `ChatInputArea` 外层组件** —— 其 `expand` 默认 `true` 会渲染成全屏浮层。
 *
 * 交互契约：
 * - `Enter` 提交、`Shift + Enter` 换行（由 `ChatInputArea.Inner` 内建处理，见其 onPressEnter 分支）；
 * - 去空白后为空 → 不提交、不跳转、保持焦点、不产生错误提示；
 * - 提交 → 跳转 `/chat?q=<encodeURIComponent(问题)>`；
 * - 禁用（`empty` / `error`）时给出原因文案，不静默禁用；
 * - `/` 与 `Ctrl/Cmd+K` 聚焦输入框（`useHotkey`）。
 */

// 深路径导入（T042）：`@lobehub/ui/chat` 的 barrel 会连带拉入 ChatItem → Markdown → shiki，
// 使首屏包体积失控；这里只取提问框所需的两个组件。
// ⚠️ 必须用具名 `ChatInputAreaInner`：该模块的**默认导出是外层 `ChatInputArea`**，
// 其 `expand` 默认 true 会渲染全屏浮层（styles.fullscreen）盖住整页（plan D11 的坑）。
import { ChatInputAreaInner, ChatSendButton } from '@lobehub/ui/es/chat/ChatInputArea/index';
import { Button, Text } from '@lobehub/ui/base-ui';
import { theme } from 'antd';
import { useCallback, useMemo, useRef, useState, type CSSProperties } from 'react';
import { useNavigate } from 'react-router';

import { zhCN } from '@/content/zh-CN';
import { askDisabledReason, canAsk, isEmptyQuestion, type ViewState } from '@/features/home/state';
import { useHotkey } from '@/hooks/useHotkey';

/** 提问框最大宽度（ui-contract §2.2：居中、最大 720px） */
const MAX_WIDTH = 720;

export interface AskBoxProps {
  /** 首页视图状态，决定是否禁用与原因文案 */
  state: ViewState;
  /** 提交成功后的回调（US4 将在此写入会话摘要） */
  onSubmit?: (question: string) => void;
}

export default function AskBox({ state, onSubmit }: AskBoxProps) {
  const [value, setValue] = useState('');
  const [focused, setFocused] = useState(false);
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const navigate = useNavigate();
  const { token } = theme.useToken();

  const disabled = !canAsk(state);
  const reason = askDisabledReason(state);

  useHotkey(textareaRef, { enabled: !disabled });

  const submit = useCallback(() => {
    if (disabled) return;

    const question = value.trim();
    if (isEmptyQuestion(question)) {
      // 空内容：不提交、不跳转、保持焦点、不报错（FR-004）
      textareaRef.current?.focus();
      return;
    }

    onSubmit?.(question);
    navigate(`/chat?q=${encodeURIComponent(question)}`);
  }, [disabled, navigate, onSubmit, value]);

  const containerStyle = useMemo<CSSProperties>(
    () => ({
      display: 'flex',
      flexDirection: 'column',
      width: '100%',
      maxWidth: MAX_WIDTH,
      margin: '0 auto',
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
    <div style={containerStyle}>
      <ChatInputAreaInner
        ref={textareaRef}
        autoSize={{ minRows: 2, maxRows: 6 }}
        disabled={disabled}
        // 禁用态把原因放在下方（红色文案），此处不再重复一遍占位符
        placeholder={disabled ? '' : zhCN.ask.placeholder}
        style={{ fontSize: 16 }}
        value={value}
        onBlur={() => setFocused(false)}
        onChange={(event) => setValue(event.target.value)}
        onFocus={() => setFocused(true)}
        onSend={submit}
      />

      {disabled ? (
        // 禁用态：ChatSendButton 不暴露 disabled，且禁用时不应给出快捷键提示
        <div style={footerStyle}>
          <Text type="danger">{reason ?? zhCN.ask.disabledError}</Text>
          <Button disabled type="primary">
            {zhCN.ask.send}
          </Button>
        </div>
      ) : (
        <ChatSendButton
          aria-disabled={false}
          onSend={submit}
          texts={{ send: zhCN.ask.send, warp: zhCN.ask.warp }}
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
