package com.unirag.upload;

import com.unirag.common.web.ApiException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FileTypeValidator 单测（tasks.md T006，022 扩展）：白名单含 pdf/docx、
 * 按类型分派的魔数/文本嗅探、mime 推断、文件名归一化回归。
 */
class FileTypeValidatorTest {

    private final FileTypeValidator validator = new FileTypeValidator();

    @Test
    void whitelistAcceptsPdfAndDocxCaseInsensitively() {
        validator.validateFileName("报告.pdf");
        validator.validateFileName("报告.PDF");
        validator.validateFileName("计划.Docx");
        validator.validateFileName("说明.txt");
        validator.validateFileName("说明.markdown");
    }

    @Test
    void whitelistRejectsUnsupportedExtensions() {
        assertThatThrownBy(() -> validator.validateFileName("数据.xlsx"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("UNSUPPORTED_FILE_TYPE");
        assertThatThrownBy(() -> validator.validateFileName("无扩展名"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("无法识别文件扩展名");
    }

    @Test
    void textContentStillValidatedAsUtf8WithoutNul() {
        validator.validateContent("a.txt", "正常文本".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> validator.validateContent("a.txt",
                new byte[]{(byte) 0xFF, (byte) 0xFE}))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("不是合法的 UTF-8 文本");
        assertThatThrownBy(() -> validator.validateContent("a.txt",
                new byte[]{'a', 0, 'b'}))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("NUL");
    }

    @Test
    void utf8TextRenamedToPdfIsRejectedByMagicSniffing() {
        byte[] textBytes = "一、总体要求".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> validator.validateContent("伪装.pdf", textBytes))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("内容与扩展名不符");
    }

    @Test
    void pdfMagicIsValidated() {
        byte[] valid = concat(new byte[]{'%', 'P', 'D', 'F', '-'}, "%PDF-1.7 body".getBytes(StandardCharsets.UTF_8));
        validator.validateContent("ok.pdf", valid);

        assertThatThrownBy(() -> validator.validateContent("bad.pdf",
                "not a pdf at all".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("内容与扩展名不符");
        assertThatThrownBy(() -> validator.validateContent("tiny.pdf", new byte[]{'%', 'P'}))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void docxZipMagicIsValidated() {
        byte[] zipHead = {'P', 'K', 3, 4, 0x14, 0x00};
        validator.validateContent("ok.docx", zipHead);

        assertThatThrownBy(() -> validator.validateContent("bad.docx",
                "plain text".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("内容与扩展名不符");
    }

    @Test
    void mimeTypeOfNewFormats() {
        assertThat(validator.mimeTypeOf("a.pdf")).isEqualTo("application/pdf");
        assertThat(validator.mimeTypeOf("a.docx"))
                .isEqualTo("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        assertThat(validator.mimeTypeOf("a.md")).isEqualTo("text/markdown");
        assertThat(validator.mimeTypeOf("a.txt")).isEqualTo("text/plain");
    }

    @Test
    void sanitizeFileNameUnchanged() {
        assertThat(validator.sanitizeFileName("../evil/\\报告.pdf")).isEqualTo("报告.pdf");
        assertThat(validator.sanitizeFileName("a.txt")).isEqualTo("a.txt");
    }

    private byte[] concat(byte[] head, byte[] tail) {
        byte[] out = new byte[head.length + tail.length];
        System.arraycopy(head, 0, out, 0, head.length);
        System.arraycopy(tail, 0, out, head.length, tail.length);
        return out;
    }
}
