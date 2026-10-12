/**
 * 问答上下文页（T022 / FR-006，ui-contract §2.4）。
 *
 * 本期为**诚实占位**（plan D7）：检索问答接口（spec 006）尚不存在，
 * 因此这里只呈现用户带来的问题与"能力尚未上线"的说明，
 * **绝不生成任何模拟答案、引用片段或伪造的检索结果**。
 */

import { Empty } from '@lobehub/ui';
import { Alert, Button, Text } from '@lobehub/ui/base-ui';
import { ChatItem } from '@lobehub/ui/chat';
import type { CSSProperties } from 'react';
import { useNavigate, useSearchParams } from 'react-router';

import { zhCN } from '@/content/zh-CN';

const HOME_HREF = '/';

export default function ChatPlaceholderPage() {
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const question = (searchParams.get('q') ?? '').trim();

  const goHome = () => {
    navigate(HOME_HREF);
  };

  if (!question) {
    return (
      <Empty
        action={
          <Button type="fill" onClick={goHome}>
            {zhCN.chat.backHome}
          </Button>
        }
        description={zhCN.chat.missingQuestionHint}
        title={zhCN.chat.missingQuestion}
        type="page"
      />
    );
  }

  return (
    <div style={pageStyle}>
      <ChatItem
        avatar={{ title: zhCN.chat.yourQuestion }}
        message={question}
        placement="right"
        showAvatar={false}
        showTitle
        // 用户自己输入的内容按纯文本原样展示（不走 Markdown 渲染）
        renderMessage={() => <Text style={questionStyle}>{question}</Text>}
      />

      <Alert
        description={zhCN.chat.notReadyDescription}
        showIcon
        title={zhCN.chat.notReadyTitle}
        type="info"
      />

      <div>
        <Button type="fill" onClick={goHome}>
          {zhCN.chat.backHome}
        </Button>
      </div>
    </div>
  );
}

const pageStyle: CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 20,
  width: '100%',
  maxWidth: 880,
  margin: '0 auto',
  padding: '24px 0',
};

const questionStyle: CSSProperties = {
  whiteSpace: 'pre-wrap',
  wordBreak: 'break-word',
};
