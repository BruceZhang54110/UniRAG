/**
 * T035 / US3 测试：最小上传对话框。
 *
 * 覆盖 ui-contract §2.8 的可断言行为：
 * 前置校验失败**不发起请求**、四种结果文案映射、202 降级不得判为失败。
 *
 * 实现事实（决定了断言写法）：
 * 1. `@lobehub/ui` 的 `Upload` 会先按 `accept` 过滤文件，再交给 `onFiles`；
 *    因此非法扩展名根本不会到达组件逻辑，尺寸限制才由本组件校验。
 * 2. 这里 mock 掉 `@/api/documents` 的 `uploadDocument`，而不是篡改全局 `XMLHttpRequest`
 *    ——后者会干扰 antd-style 等库在 jsdom 中的行为，导致测试进程异常缓慢。
 */

import { fireEvent, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { uploadDocument, type UploadResult } from '@/api/documents';
import { zhCN } from '@/content/zh-CN';
import UploadDialog, {
  MAX_UPLOAD_BYTES,
  defaultDocName,
  uploadResultText,
  validateDocName,
  validateFile,
} from '@/features/upload/UploadDialog';
import { renderWithProviders } from '@/test/render';

vi.mock('@/api/documents', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/documents')>();
  return { ...actual, uploadDocument: vi.fn() };
});

const uploadMock = vi.mocked(uploadDocument);

const BASE_RESULT: UploadResult = {
  docName: 'handbook',
  title: null,
  status: 'INGESTED',
  version: 1,
  chunkCount: 12,
  fileSizeBytes: 1024,
  pipelines: [],
};

function makeFile(name: string, size = 1024): File {
  const file = new File(['# hello'], name, { type: 'text/markdown' });
  Object.defineProperty(file, 'size', { value: size });
  return file;
}

/** 选择文件，并等到默认的文档标识已写入表单（否则提交闭包会读到空值） */
async function pickFile(name: string, size = 1024) {
  const input = document.querySelector('input[type="file"]') as HTMLInputElement;
  fireEvent.change(input, { target: { files: [makeFile(name, size)] } });
  await waitFor(() => expect(screen.getByLabelText(zhCN.upload.docNameLabel)).toHaveValue(defaultDocName(name)));
}

function clickSubmit() {
  fireEvent.click(screen.getByRole('button', { name: zhCN.upload.submit }));
}

afterEach(() => {
  vi.clearAllMocks();
});

describe('上传校验纯函数', () => {
  it('只接受 txt / md / markdown，大小写不敏感', () => {
    expect(validateFile(makeFile('a.txt'))).toBeNull();
    expect(validateFile(makeFile('a.md'))).toBeNull();
    expect(validateFile(makeFile('a.MARKDOWN'))).toBeNull();
    expect(validateFile(makeFile('a.pdf'))).toBe(zhCN.upload.invalidType);
  });

  it('超过 10 MB 直接拒绝', () => {
    expect(validateFile(makeFile('a.md', MAX_UPLOAD_BYTES + 1))).toBe(zhCN.upload.fileTooLarge);
    expect(validateFile(makeFile('a.md', MAX_UPLOAD_BYTES))).toBeNull();
  });

  it('文档标识非空且不超过 255 字符', () => {
    expect(validateDocName('   ')).toBe(zhCN.upload.docNameRequired);
    expect(validateDocName('a'.repeat(256))).toBe(zhCN.upload.docNameTooLong);
    expect(validateDocName('handbook')).toBeNull();
  });

  it('文件名去扩展名作为默认文档标识', () => {
    expect(defaultDocName('员工手册.md')).toBe('员工手册');
    expect(defaultDocName('no-extension')).toBe('no-extension');
  });

  it('四种上传结果都有对应文案', () => {
    expect(uploadResultText('INGESTED')).toBe(zhCN.upload.results.INGESTED);
    expect(uploadResultText('NOOP')).toBe(zhCN.upload.results.NOOP);
    expect(uploadResultText('PARTIAL')).toBe(zhCN.upload.results.PARTIAL);
    expect(uploadResultText('FAILED')).toBe(zhCN.upload.results.FAILED);
  });
});

describe('UploadDialog', () => {
  it('超大文件就地提示，且提交时不发起请求', async () => {
    renderWithProviders(<UploadDialog open onClose={vi.fn()} onUploaded={vi.fn()} />);

    await pickFile('handbook.md', MAX_UPLOAD_BYTES + 1);
    expect(await screen.findByTestId('upload-issue')).toHaveTextContent(zhCN.upload.fileTooLarge);

    clickSubmit();
    expect(uploadMock).not.toHaveBeenCalled();
  });

  it('非法扩展名被 accept 过滤，不进入上传流程也不发请求', async () => {
    renderWithProviders(<UploadDialog open onClose={vi.fn()} onUploaded={vi.fn()} />);

    const input = document.querySelector('input[type="file"]') as HTMLInputElement;
    fireEvent.change(input, { target: { files: [makeFile('report.pdf')] } });

    // 未选中任何文件：界面仍显示选择提示，提交时提示"请先选择文件"
    expect(screen.getByText(zhCN.upload.pickFile)).toBeInTheDocument();

    clickSubmit();
    expect(await screen.findByTestId('upload-issue')).toHaveTextContent(zhCN.upload.fileRequired);
    expect(uploadMock).not.toHaveBeenCalled();
  });

  it('合法文件上传成功后展示入库文案并通知首页刷新', async () => {
    uploadMock.mockResolvedValue({ kind: 'completed', result: BASE_RESULT });
    const onUploaded = vi.fn();
    renderWithProviders(<UploadDialog open onClose={vi.fn()} onUploaded={onUploaded} />);

    await pickFile('handbook.md');
    clickSubmit();

    expect(await screen.findByTestId('upload-result')).toHaveTextContent(zhCN.upload.results.INGESTED);
    expect(uploadMock).toHaveBeenCalledTimes(1);
    expect(uploadMock.mock.calls[0][0]).toMatchObject({ docName: 'handbook' });
    expect(onUploaded).toHaveBeenCalledTimes(1);
  });

  it('重复内容返回 NOOP 时展示"已跳过重复处理"', async () => {
    uploadMock.mockResolvedValue({ kind: 'completed', result: { ...BASE_RESULT, status: 'NOOP' } });
    renderWithProviders(<UploadDialog open onClose={vi.fn()} onUploaded={vi.fn()} />);

    await pickFile('handbook.md');
    clickSubmit();

    expect(await screen.findByTestId('upload-result')).toHaveTextContent(zhCN.upload.results.NOOP);
  });

  it('入库失败展示失败文案并允许重试', async () => {
    uploadMock.mockResolvedValue({ kind: 'completed', result: { ...BASE_RESULT, status: 'FAILED' } });
    renderWithProviders(<UploadDialog open onClose={vi.fn()} onUploaded={vi.fn()} />);

    await pickFile('handbook.md');
    clickSubmit();

    expect(await screen.findByTestId('upload-result')).toHaveTextContent(zhCN.upload.results.FAILED);
  });

  it('异步 202 走降级分支：提示已接收，不判为失败', async () => {
    uploadMock.mockResolvedValue({ kind: 'accepted' });
    renderWithProviders(<UploadDialog open onClose={vi.fn()} onUploaded={vi.fn()} />);

    await pickFile('handbook.md');
    clickSubmit();

    const result = await screen.findByTestId('upload-result');
    expect(result).toHaveTextContent(zhCN.upload.results.accepted);
    expect(screen.queryByTestId('upload-failure')).not.toBeInTheDocument();
  });

  it('请求失败时给出可重试的错误提示', async () => {
    uploadMock.mockRejectedValue(new Error('boom'));
    renderWithProviders(<UploadDialog open onClose={vi.fn()} onUploaded={vi.fn()} />);

    await pickFile('handbook.md');
    clickSubmit();

    expect(await screen.findByTestId('upload-failure')).toBeInTheDocument();
  });
});
