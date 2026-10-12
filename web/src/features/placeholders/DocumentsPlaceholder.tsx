/** `/documents` 占位页（T016）：文档库由后续 feature 实现。 */

import PlaceholderPage from '@/features/placeholders/PlaceholderPage';
import { zhCN } from '@/content/zh-CN';

export default function DocumentsPlaceholder() {
  return (
    <PlaceholderPage
      description={zhCN.placeholders.documents.description}
      title={zhCN.placeholders.documents.title}
    />
  );
}
