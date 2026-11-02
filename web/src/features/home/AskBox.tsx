/**
 * 首页提问框（T021 / FR-003–FR-006，ui-contract §2.2）。
 *
 * 018 起容器与发送行由共享 `AskComposer` 承载（首页与对话页同一份视觉契约）；
 * 本组件只保留首页特有行为：
 * - `Enter` 提交、`Shift + Enter` 换行（由 `ChatInputAreaInner` 内建处理）；
 * - 去空白后为空 → 不提交、不跳转、保持焦点、不产生错误提示；
 * - 提交 → 跳转 `/chat?q=<encodeURIComponent(问题)>`；
 * - 禁用（`empty` / `error`）时给出原因文案，不静默禁用；
 * - `/` 与 `Ctrl/Cmd+K` 聚焦输入框（`useHotkey`）。
 */

import { Button, Text } from '@lobehub/ui/base-ui';
import { useCallback, useRef, useState, type CSSProperties } from 'react';
import { useNavigate } from 'react-router';

import AskComposer from '@/components/AskComposer';
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
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const navigate = useNavigate();

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

  return (
    <div style={wrapperStyle}>
      <AskComposer
        inputRef={textareaRef}
        ariaLabel={zhCN.ask.placeholder}
        autoSize={{ minRows: 2, maxRows: 6 }}
        disabled={disabled}
        fontSize={16}
        // 禁用态把原因放在下方（红色文案），此处不再重复一遍占位符
        placeholder={disabled ? '' : zhCN.ask.placeholder}
        testId="home-ask-box"
        value={value}
        footer={
          disabled ? (
            <>
              <Text type="danger">{reason ?? zhCN.ask.disabledError}</Text>
              <Button disabled type="primary">
                {zhCN.ask.send}
              </Button>
            </>
          ) : undefined
        }
        onChange={setValue}
        onSend={submit}
      />
    </div>
  );
}

const wrapperStyle: CSSProperties = {
  width: '100%',
  maxWidth: MAX_WIDTH,
  margin: '0 auto',
};
