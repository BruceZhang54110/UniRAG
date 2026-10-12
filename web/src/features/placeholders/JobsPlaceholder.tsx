/** `/jobs` 占位页（T016）：任务中心由后续 feature 实现。 */

import PlaceholderPage from '@/features/placeholders/PlaceholderPage';
import { zhCN } from '@/content/zh-CN';

export default function JobsPlaceholder() {
  return (
    <PlaceholderPage description={zhCN.placeholders.jobs.description} title={zhCN.placeholders.jobs.title} />
  );
}
