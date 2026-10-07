package com.unirag.upload.dto;

import java.util.List;

/**
 * 文档列表分页结果（contracts §4）：{@code { total, items }}。
 *
 * @param total 本租户文档总数
 * @param items 当前页列表项（按创建时间倒序）
 */
public record DocumentPage(long total, List<DocumentInfoItem> items) {
}
