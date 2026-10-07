package com.unirag.upload;

import com.unirag.upload.support.ApiException;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * 文件校验（spec FR-003 双重判断，research R6）：
 * 扩展名白名单 + 内容嗅探（可完整解码 UTF-8 且无 NUL 字节）；文件名归一化防路径穿越。
 */
@Component
public class FileTypeValidator {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("txt", "md", "markdown");
    private static final int MAX_FILE_NAME_LENGTH = 512;

    /**
     * 校验字节内容：UTF-8 完整可解码且无 NUL 字节（挡住"改名伪装"的二进制文件）。
     */
    public void validateContent(byte[] bytes) {
        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            if (decoded.chars().anyMatch(ch -> ch == 0)) {
                throw new ApiException("UNSUPPORTED_FILE_TYPE", "文件内容包含 NUL 字节，不是纯文本/Markdown");
            }
        } catch (CharacterCodingException e) {
            throw new ApiException("UNSUPPORTED_FILE_TYPE", "文件内容不是合法的 UTF-8 文本（v1 仅支持纯文本/Markdown）");
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
            throw new ApiException("UNSUPPORTED_FILE_TYPE", "无法识别文件扩展名（v1 支持: txt/md/markdown）");
        }
        String ext = originalFilename.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw new ApiException("UNSUPPORTED_FILE_TYPE",
                    "不支持的文件类型: " + ext + "（v1 支持: txt/md/markdown）");
        }
    }

    /** 按扩展名推断 mime（v1 文本类）。 */
    public String mimeTypeOf(String originalFilename) {
        int dot = originalFilename.lastIndexOf('.');
        String ext = originalFilename.substring(dot + 1).toLowerCase(Locale.ROOT);
        return switch (ext) {
            case "md", "markdown" -> "text/markdown";
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
}
