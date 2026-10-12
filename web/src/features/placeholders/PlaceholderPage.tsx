/**
 * 占位页共用骨架（T016）：以 `Empty` 呈现"即将上线"语义，而不是空白页。
 * 页面级占位都必须有标题、说明与返回首页的出口，避免死链。
 */

import { Empty } from '@lobehub/ui';
import { Button } from '@lobehub/ui/base-ui';
import { useNavigate } from 'react-router';

import { zhCN } from '@/content/zh-CN';

export interface PlaceholderPageProps {
  title: string;
  description: string;
}

const HOME_HREF = '/';

export default function PlaceholderPage({ title, description }: PlaceholderPageProps) {
  const navigate = useNavigate();

  return (
    <Empty
      action={
        <Button
          type="fill"
          onClick={() => {
            navigate(HOME_HREF);
          }}
        >
          {zhCN.placeholders.backHome}
        </Button>
      }
      description={description}
      title={title}
      type="page"
    />
  );
}
