/**
 * SSE 解析器测试（016-sse-chat-streaming T13）：多事件流、data 多行、event 缺省、
 * 半包增量续传、注释/心跳行容忍。
 */

import { describe, expect, it } from 'vitest';

import { SseParser } from '@/api/sse-parse';

describe('SseParser', () => {
  it('解析完整的多事件流', () => {
    const parser = new SseParser();
    const events = parser.feed(
      'event: message\ndata: {"delta":"甲"}\n\nevent: done\ndata: {"answer":"甲乙"}\n\n',
    );
    expect(events).toEqual([
      { event: 'message', data: '{"delta":"甲"}' },
      { event: 'done', data: '{"answer":"甲乙"}' },
    ]);
  });

  it('data 多行拼接为一个事件', () => {
    const parser = new SseParser();
    const events = parser.feed('event: message\ndata: 第一行\ndata: 第二行\n\n');
    expect(events).toEqual([{ event: 'message', data: '第一行\n第二行' }]);
  });

  it('event 缺省按 message 处理，注释行被忽略', () => {
    const parser = new SseParser();
    const events = parser.feed(': heartbeat\ndata: {"delta":"x"}\n\n');
    expect(events).toEqual([{ event: 'message', data: '{"delta":"x"}' }]);
  });

  it('半包留在缓冲，下一个 chunk 续传后产出完整事件', () => {
    const parser = new SseParser();
    expect(parser.feed('event: message\nda')).toEqual([]);
    expect(parser.feed('ta: {"delta":"半"}')).toEqual([]);
    expect(parser.feed('\n\nevent: done\ndata: {}')).toEqual([
      { event: 'message', data: '{"delta":"半"}' },
    ]);
    expect(parser.feed('\n\n')).toEqual([{ event: 'done', data: '{}' }]);
  });

  it('容忍 CRLF 行尾', () => {
    const parser = new SseParser();
    expect(parser.feed('event: message\r\ndata: {"delta":"a"}\r\n\r\n')).toEqual([
      { event: 'message', data: '{"delta":"a"}' },
    ]);
  });
});
