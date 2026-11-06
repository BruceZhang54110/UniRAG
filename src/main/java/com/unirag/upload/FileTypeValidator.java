package com.unirag.upload;

import com.unirag.common.web.ApiException;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * 文件校验（spec FR-003 双重判断，research R6 / R2；022 扩展 pdf/docx）：
 * 扩展名白名单 + 按类型分派的内容嗅探（文本类：UTF-8 完整解码且无 NUL；
 * PDF：魔数 {@code %PDF-}；DOCX：ZIP 本地文件头 {@code PK\x03\x04}——容器内容不符
 * 由解析阶段兜底，见 spec FR-002）；文件名归一化防路径穿越。
 */
@Component
public class FileTypeValidator {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("txt", "md", "markdown", "pdf", "docx");
    private static final int MAX_FILE_NAME_LENGTH = 512;

    private static final byte[] PDF_MAGIC = {(byte) '%', (byte) 'P', (byte) 'D', (byte) 'F', (byte) '-'};
    private static final byte[] ZIP_MAGIC = {(byte) 'P', (byte) 'K', 3, 4};

    /**
     * 校验字节内容（按扩展名分派，spec FR-002 / clarify Q2）：文本类保留 UTF-8/NUL 嗅探；
     * PDF 校验魔数前 5 字节；DOCX 校验 ZIP 头前 4 字节（与 xlsx/pptx 同魔数，深度校验交给解析器）。
     */
    public void validateContent(String originalFilename, byte[] bytes) {
        String ext = extensionOf(originalFilename);
        if ("pdf".equals(ext)) {
            requireMagic(bytes, PDF_MAGIC, "文件内容与扩展名不符：不是有效的 PDF");
            return;
        }
        if ("docx".equals(ext)) {
            requireMagic(bytes, ZIP_MAGIC, "文件内容与扩展名不符：不是有效的 DOCX（ZIP 容器）");
            return;
        }
        validateTextContent(bytes);
    }

    private void validateTextContent(byte[] bytes) {
        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            if (decoded.chars().anyMatch(ch -> ch == 0)) {
                throw new ApiException("UNSUPPORTED_FILE_TYPE", "文件内容包含 NUL 字节，不是纯文本/Markdown");
            }
        } catch (CharacterCodingException e) {
            throw new ApiException("UNSUPPORTED_FILE_TYPE", "文件内容不是合法的 UTF-8 文本");
        }
    }

    private void requireMagic(byte[] bytes, byte[] magic, String message) {
        if (bytes == null || bytes.length < magic.length) {
            throw new ApiException("UNSUPPORTED_FILE_TYPE", message);
        }
        for (int i = 0; i < magic.length; i++) {
            if (bytes[i] != magic[i]) {
                throw new ApiException("UNSUPPORTED_FILE_TYPE", message);
            }
        }
    }

    /**
     * 校验原始文件名：白名单扩展名（大小写不敏感）。
     */
    public void validateFileName(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new ApiException("VALIDATION_FAILED", "缺少原始文件名");
        }
        int dot = originalFilename.lastIndexOf('.');
        if (dot < 0 || dot == originalFilename.length() - 1) {
            throw new ApiException("UNSUPPORTED_FILE_TYPE", "无法识别文件扩展名（支持: txt/md/markdown/pdf/docx）");
        }
        String ext = originalFilename.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw new ApiException("UNSUPPORTED_FILE_TYPE",
                    "不支持的文件类型: " + ext + "（支持: txt/md/markdown/pdf/docx）");
        }
    }

    /** 按扩展名推断 mime。 */
    public String mimeTypeOf(String originalFilename) {
        return switch (extensionOf(originalFilename)) {
            case "md", "markdown" -> "text/markdown";
            case "pdf" -> "application/pdf";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            default -> "text/plain";
        };
    }

    /**
     * 文件名归一化：剔除路径分隔符与 parent 引用、压缩空白、截断长度、非可见字符替换为 '_'，
     * 仅用于入库展示与下载 header，绝不做文件系统路径拼接（防路径穿越，spec 边界）。
     */
    public String sanitizeFileName(String originalFilename) {
        String name = originalFilename.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.replace("..", "_").replaceAll("[\\p{Cntrl}]", "_").trim();
        if (name.isEmpty()) {
            name = "unnamed";
        }
        return name.length() > MAX_FILE_NAME_LENGTH ? name.substring(0, MAX_FILE_NAME_LENGTH) : name;
    }

    private static String extensionOf(String originalFilename) {
        int dot = originalFilename.lastIndexOf('.');
        return dot < 0 ? "" : originalFilename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
