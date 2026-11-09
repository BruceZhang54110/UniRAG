package com.unirag.retrieval.query;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 引用继承配置（023，contracts/citation-inheritance.md §2.3）：总开关 + 指代词表。
 *
 * <p>词表保守起步、可配置调整——"宁可漏触发不可错锁定"，调优依据是观测中的
 * 触发率/放弃率与评测中的误锁率（spec US2/US3）。匹配为预编译正则，任一命中即触发。
 */
@ConfigurationProperties(prefix = "unirag.citation-inheritance")
public record CitationInheritanceProperties(@DefaultValue("true") boolean enabled,
                                            @DefaultValue({"这篇", "该文档", "此文档", "这份文件", "这份方案",
                                                    "上述文档", "其中", "上面提到", "上面说的", "上面讲的",
                                                    "刚才提到", "刚才说", "前文提到", "前文说"})
                                            List<String> demonstratives) {

    private static final Pattern SAFE_TERM = Pattern.compile("^[\\p{IsHan}A-Za-z0-9]{1,12}$");

    public CitationInheritanceProperties {
        if (demonstratives == null || demonstratives.isEmpty()) {
            throw new IllegalArgumentException("unirag.citation-inheritance.demonstratives 不能为空");
        }
        for (String term : demonstratives) {
            if (term == null || !SAFE_TERM.matcher(term).matches()) {
                throw new IllegalArgumentException("unirag.citation-inheritance.demonstratives 含非法词项: " + term);
            }
        }
        demonstratives = List.copyOf(demonstratives);
    }

    /** 预编译命中检测：任一词项出现在问题中即视为指代追问。 */
    public boolean matchesAnaphora(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        for (String term : demonstratives) {
            if (question.contains(term)) {
                return true;
            }
        }
        return false;
    }

    /** 命中的第一个词项（观测/日志用）；未命中返回 null。 */
    public String matchedTerm(String question) {
        if (question == null) {
            return null;
        }
        for (String term : demonstratives) {
            if (question.contains(term)) {
                return term;
            }
        }
        return null;
    }
}
