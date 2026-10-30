/**
 * SSE 增量解析器（016-sse-chat-streaming，contracts/ask-stream-endpoint.md）。
 *
 * 纯函数式、可增量喂入：fetch 流式读取的每个 chunk 经 `feed()` 解析，
 * 以空行为事件分隔（容忍 \r\n），`event:` 缺省为 `message`，`data:` 可多行拼接。
 *  half-event 残块留在缓冲中等下一个 chunk。
 */

export interface SseEvent {
  /** 事件名；SSE 约定缺省为 message */
  event: string;
  /** data 区拼接文本（JSON 由消费方解析） */
  data: string;
}

export class SseParser {
  private buffer = '';

  private pendingEvent = 'message';

  feed(chunk: string): SseEvent[] {
    this.buffer += chunk.replace(/\r\n/g, '\n');
    const events: SseEvent[] = [];
    for (;;) {
      const separator = this.buffer.indexOf('\n\n');
      if (separator < 0) break;
      const block = this.buffer.slice(0, separator);
      this.buffer = this.buffer.slice(separator + 2);
      this.parseBlock(block, events);
    }
    return events;
  }

  private parseBlock(block: string, events: SseEvent[]): void {
    const dataLines: string[] = [];
    for (const line of block.split('\n')) {
      if (line.startsWith(':') || line === '') continue;   // 注释/心跳行
      if (line.startsWith('event:')) {
        this.pendingEvent = line.slice(6).trim();
      } else if (line.startsWith('data:')) {
        dataLines.push(line.slice(5).replace(/^ /, ''));
      }
    }
    if (dataLines.length === 0) return;
    events.push({ event: this.pendingEvent || 'message', data: dataLines.join('\n') });
  }
}
