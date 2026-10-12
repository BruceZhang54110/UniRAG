/**
 * 最近对话本地存储（T037 / FR-013、FR-014，data-model §1.5）。
 *
 * 契约：
 * - 键 `unirag.recent-chats`，最多 6 条，按 `lastActiveAt` 倒序；
 * - 问题摘要落盘前截断至 80 字符；
 * - 脏数据（非数组、字段缺失、JSON 解析失败）**整体清空重置**，不抛错、不阻塞首页。
 */

import { useCallback, useState } from 'react';

/** localStorage 键名（data-model §4） */
export const RECENT_CHATS_KEY = 'unirag.recent-chats';

/** 最多保留的会话条数 */
export const RECENT_CHAT_LIMIT = 6;

/** 问题摘要截断长度 */
export const QUESTION_MAX_LENGTH = 80;

export interface ChatSessionSummary {
  id: string;
  question: string;
  lastActiveAt: number;
  messageCount: number;
}

export function summarizeQuestion(question: string): string {
  const normalized = question.trim().replace(/\s+/g, ' ');
  return normalized.length > QUESTION_MAX_LENGTH
    ? `${normalized.slice(0, QUESTION_MAX_LENGTH)}…`
    : normalized;
}

function isValidSummary(value: unknown): value is ChatSessionSummary {
  if (typeof value !== 'object' || value === null) return false;
  const item = value as Record<string, unknown>;
  return (
    typeof item.id === 'string' &&
    item.id !== '' &&
    typeof item.question === 'string' &&
    typeof item.lastActiveAt === 'number' &&
    Number.isFinite(item.lastActiveAt)
  );
}

/** 解析存储内容；任何不合法输入都返回空数组（调用方负责清理） */
export function parseRecentChats(raw: string | null): ChatSessionSummary[] {
  if (!raw) return [];
  try {
    const parsed: unknown = JSON.parse(raw);
    if (!Array.isArray(parsed)) return [];
    const valid = parsed.filter(isValidSummary);
    if (valid.length !== parsed.length) return [];
    return valid
      .map((item) => ({ ...item, messageCount: Number.isFinite(item.messageCount) ? item.messageCount : 1 }))
      .sort((left, right) => right.lastActiveAt - left.lastActiveAt)
      .slice(0, RECENT_CHAT_LIMIT);
  } catch {
    return [];
  }
}

/** 追加一条会话摘要并保持上限与倒序（纯函数） */
export function appendSession(
  list: ChatSessionSummary[],
  question: string,
  now: number = Date.now(),
  id: string = `session-${now}-${Math.random().toString(36).slice(2, 8)}`,
): ChatSessionSummary[] {
  const entry: ChatSessionSummary = {
    id,
    question: summarizeQuestion(question),
    lastActiveAt: now,
    messageCount: 1,
  };
  return [entry, ...list].slice(0, RECENT_CHAT_LIMIT);
}

function readFromStorage(): ChatSessionSummary[] {
  if (typeof window === 'undefined') return [];
  const raw = window.localStorage.getItem(RECENT_CHATS_KEY);
  const parsed = parseRecentChats(raw);
  if (raw && parsed.length === 0) {
    // 脏数据：清空而非反复解析失败
    window.localStorage.removeItem(RECENT_CHATS_KEY);
  }
  return parsed;
}

function writeToStorage(list: ChatSessionSummary[]): void {
  if (typeof window === 'undefined') return;
  try {
    window.localStorage.setItem(RECENT_CHATS_KEY, JSON.stringify(list));
  } catch {
    // 隐私模式或配额不足：静默降级，最近对话非核心能力
  }
}

export interface UseRecentChatsResult {
  items: ChatSessionSummary[];
  record: (question: string) => void;
}

export function useRecentChats(): UseRecentChatsResult {
  const [items, setItems] = useState<ChatSessionSummary[]>(readFromStorage);

  const record = useCallback((question: string) => {
    setItems((current) => {
      const next = appendSession(current, question);
      writeToStorage(next);
      return next;
    });
  }, []);

  return { items, record };
}
