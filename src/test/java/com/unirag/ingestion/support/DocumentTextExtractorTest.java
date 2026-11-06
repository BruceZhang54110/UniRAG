package com.unirag.ingestion.support;

import com.unirag.common.web.ApiException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DocumentTextExtractor 单测（tasks.md T006）：txt/md 直通、程序生成 PDF/DOCX 抽取、
 * 截断 PDF → PARSE_FAILED、无文本层 PDF → EMPTY_EXTRACTED_TEXT、未知扩展名拒绝。
 * fixture 由 PDFBox/POI（Tika 传递依赖）程序化生成，不存二进制样本。
 */
class DocumentTextExtractorTest {

    private final DocumentTextExtractor extractor = new DocumentTextExtractor();

    @Test
    void textTypesPassThroughAsUtf8() {
        byte[] bytes = "一、总体要求\n坚持原则。\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        assertThat(extractor.extract("报告.txt", bytes)).isEqualTo("一、总体要求\n坚持原则。\n");
        assertThat(extractor.extract("报告.MD", bytes)).isEqualTo("一、总体要求\n坚持原则。\n");
    }

    @Test
    void extractsTextFromGeneratedPdf() throws IOException {
        byte[] pdf = pdfWithText("General Requirements", "Stick to the basic principles");

        String text = extractor.extract("公文.pdf", pdf);

        assertThat(text).contains("General Requirements");
        assertThat(text).contains("Stick to the basic principles");
    }

    @Test
    void extractsParagraphAndTableTextFromGeneratedDocx() throws IOException {
        byte[] docx = docxWithParagraphAndTable("发展目标", "指标名称", "完成值");

        String text = extractor.extract("计划.docx", docx);

        assertThat(text).contains("发展目标");
        assertThat(text).contains("指标名称");
        assertThat(text).contains("完成值");
    }

    @Test
    void truncatedPdfBytesAreParseFailed() {
        byte[] truncated = new byte[]{'%', 'P', 'D', 'F', '-', 1, 2, 3, 4, 5};

        assertThatThrownBy(() -> extractor.extract("损坏.pdf", truncated))
                .isInstanceOf(TextExtractionException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(TextExtractionException.CODE_PARSE_FAILED);
    }

    @Test
    void xlsxBytesRenamedToDocxAreParseFailed() throws IOException {
        // ZIP 魔数与 DOCX 相同（clarify Q2）：通过入口嗅探后由解析阶段兜底失败。
        byte[] xlsxBytes;
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook workbook =
                     new org.apache.poi.xssf.usermodel.XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            workbook.createSheet("数据").createRow(0).createCell(0).setCellValue("指标");
            workbook.write(out);
            xlsxBytes = out.toByteArray();
        }

        assertThatThrownBy(() -> extractor.extract("表格.docx", xlsxBytes))
                .isInstanceOf(TextExtractionException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(TextExtractionException.CODE_PARSE_FAILED);
    }

    @Test
    void pdfWithoutTextLayerIsEmptyExtractedText() throws IOException {
        byte[] imageOnlyPdf;
        try (PDDocument doc = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.addPage(new PDPage()); // 空页面：无任何文本绘制指令
            doc.save(out);
            imageOnlyPdf = out.toByteArray();
        }

        assertThatThrownBy(() -> extractor.extract("扫描件.pdf", imageOnlyPdf))
                .isInstanceOf(TextExtractionException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(TextExtractionException.CODE_EMPTY_EXTRACTED_TEXT);
    }

    @Test
    void unsupportedExtensionIsRejected() {
        assertThatThrownBy(() -> extractor.extract("数据.xlsx", new byte[]{1, 2, 3}))
                .isInstanceOf(TextExtractionException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(TextExtractionException.CODE_PARSE_FAILED);
    }

    private byte[] pdfWithText(String line1, String line2) throws IOException {
        try (PDDocument doc = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(doc, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(50, 700);
                stream.showText(line1);
                stream.newLineAtOffset(0, -20);
                stream.showText(line2);
                stream.endText();
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    private byte[] docxWithParagraphAndTable(String paragraph, String cell1, String cell2) throws IOException {
        try (XWPFDocument doc = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XWPFParagraph para = doc.createParagraph();
            XWPFRun run = para.createRun();
            run.setText(paragraph);
            XWPFTable table = doc.createTable(1, 2);
            XWPFTableRow row = table.getRow(0);
            row.getCell(0).setText(cell1);
            row.getCell(1).setText(cell2);
            doc.write(out);
            return out.toByteArray();
        }
    }
}
