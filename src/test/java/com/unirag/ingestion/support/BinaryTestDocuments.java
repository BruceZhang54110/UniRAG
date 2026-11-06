package com.unirag.ingestion.support;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 二进制测试语料生成器（022）：PDF/DOCX 由 PDFBox/POI（Tika 传递依赖）程序化生成，
 * 不存二进制 fixture 文件。PDF 内嵌字体为标准 14 字体（WinAnsi），文字须为 ASCII；
 * 中文语料请用 {@link #docxWithText(String)}。
 */
public final class BinaryTestDocuments {

    private BinaryTestDocuments() {
    }

    /** 生成含多行 ASCII 文字的 PDF（每行一条 showText）。 */
    public static byte[] pdfWithText(String... lines) throws IOException {
        try (PDDocument doc = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(doc, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(50, 720);
                for (String line : lines) {
                    stream.showText(line);
                    stream.newLineAtOffset(0, -18);
                }
                stream.endText();
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    /** 生成 DOCX：按空行分段，每段一个 XWPFParagraph（支持中文）。 */
    public static byte[] docxWithText(String text) throws IOException {
        try (XWPFDocument doc = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String paragraph : text.split("\n\\s*\n")) {
                if (paragraph.isBlank()) {
                    continue;
                }
                XWPFParagraph para = doc.createParagraph();
                para.createRun().setText(paragraph.replace("\n", " ").trim());
            }
            doc.write(out);
            return out.toByteArray();
        }
    }

    /** xlsx 字节（POI XSSF），用于"ZIP 容器改名 docx"伪装场景。 */
    public static byte[] xlsxBytes() throws IOException {
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook workbook =
                     new org.apache.poi.xssf.usermodel.XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            workbook.createSheet("数据").createRow(0).createCell(0).setCellValue("指标");
            workbook.write(out);
            return out.toByteArray();
        }
    }

    /** 截断的伪 PDF 头（魔数正确、内容损坏），用于 PARSE_FAILED 场景。 */
    public static byte[] truncatedPdf() {
        return "%PDF-1.7\n1 0 obj\n<<>>".getBytes(StandardCharsets.US_ASCII);
    }

    /** 无文本层的 PDF（空白页），模拟扫描件，用于 EMPTY_EXTRACTED_TEXT 场景。 */
    public static byte[] emptyPdf() throws IOException {
        try (PDDocument doc = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.addPage(new PDPage());
            doc.save(out);
            return out.toByteArray();
        }
    }
}
