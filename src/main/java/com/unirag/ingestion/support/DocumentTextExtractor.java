package com.unirag.ingestion.support;

import lombok.extern.slf4j.Slf4j;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Component;
import org.xml.sax.SAXException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * 入库边界的文本抽取器（research R3）：按扩展名分发——txt/md/markdown 走 UTF-8 解码
 * （保持改造前行为，空白语义由调用方既有校验承担）；pdf/docx 走 Tika AutoDetectParser
 * （类路径仅 pdf/microsoft 两模块，见 pom tika 依赖），把原始字节抽取为纯文本喂给既有管线。
 *
 * <p>失败语义（spec FR-004 / clarify Q1）：解析抛错 → {@code PARSE_FAILED}；解析成功但
 * 文本为空/纯空白 → {@code EMPTY_EXTRACTED_TEXT}。两者均为确定性失败，不占用重试预算。
 */
@Slf4j
@Component
public class DocumentTextExtractor {

    private static final Set<String> TEXT_EXTENSIONS = Set.of("txt", "md", "markdown");
    private static final Set<String> BINARY_EXTENSIONS = Set.of("pdf", "docx");

    private final AutoDetectParser parser = new AutoDetectParser();

    /**
     * 抽取原始文件字节为入库文本。原始字节不落本组件（存储/下载仍在调用方）。
     *
     * @param originalFilename 原始文件名（仅用于按扩展名分发与错误定位，不做路径拼接）
     * @param bytes            原始文件字节
     * @return 抽取文本（pdf/docx 为 Tika 产出文本流；文本类为 UTF-8 解码结果）
     */
    public String extract(String originalFilename, byte[] bytes) {
        String extension = extensionOf(originalFilename);
        if (TEXT_EXTENSIONS.contains(extension)) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        if (!BINARY_EXTENSIONS.contains(extension)) {
            throw new TextExtractionException(TextExtractionException.CODE_PARSE_FAILED,
                    "不支持的文件类型: " + extension + "（支持: txt/md/markdown/pdf/docx）");
        }
        return extractBinary(originalFilename, bytes);
    }

    private String extractBinary(String originalFilename, byte[] bytes) {
        String text;
        String detectedType;
        try {
            Metadata metadata = new Metadata();
            BodyContentHandler handler = new BodyContentHandler(-1);
            parser.parse(new ByteArrayInputStream(bytes), handler, metadata, new ParseContext());
            text = handler.toString();
            detectedType = metadata.get(Metadata.CONTENT_TYPE);
        } catch (IOException | SAXException | org.apache.tika.exception.TikaException | RuntimeException e) {
            log.info("文档解析失败: file={}, type={}", originalFilename, e.getClass().getSimpleName());
            throw new TextExtractionException(TextExtractionException.CODE_PARSE_FAILED,
                    "文件损坏或格式不受支持，无法提取文字");
        }
        // 容器级伪装兜底（clarify Q2）：魔数相同但真实类型不符（如 xlsx 改名 docx）按确定性失败处理。
        if (!matchesDeclaredFamily(extensionOf(originalFilename), detectedType)) {
            log.info("文档类型与扩展名不符: file={}, detected={}", originalFilename, detectedType);
            throw new TextExtractionException(TextExtractionException.CODE_PARSE_FAILED,
                    "文件内容与扩展名不符，无法按声明格式提取文字");
        }
        if (text == null || text.isBlank()) {
            throw new TextExtractionException(TextExtractionException.CODE_EMPTY_EXTRACTED_TEXT,
                    "未能从文件中提取到文字内容，可能是扫描件或纯图片文档");
        }
        log.info("文档抽取完成: file={}, sourceBytes={}, extractedChars={}",
                originalFilename, bytes.length, text.length());
        return text;
    }

    private boolean matchesDeclaredFamily(String extension, String detectedType) {
        if (detectedType == null) {
            return false;
        }
        return switch (extension) {
            case "pdf" -> detectedType.equals("application/pdf");
            case "docx" -> detectedType.startsWith("application/vnd.openxmlformats-officedocument.wordprocessingml");
            default -> true;
        };
    }

    private static String extensionOf(String originalFilename) {
        int dot = originalFilename.lastIndexOf('.');
        return dot < 0 ? "" : originalFilename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
