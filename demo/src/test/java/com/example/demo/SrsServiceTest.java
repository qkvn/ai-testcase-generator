package com.example.demo;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.*;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SrsServiceTest {

    private final SrsService service = new SrsService();

    private static final String SRS_TXT = """
            Tài liệu đặc tả yêu cầu phần mềm - Hệ thống bán hàng

            Mục lục
            1. Giới thiệu ........ 1
            2. Yêu cầu chức năng ........ 2

            1. Giới thiệu
            Tài liệu mô tả hệ thống bán hàng trực tuyến cho cửa hàng ABC.

            2. Yêu cầu chức năng
            2.1 Đăng nhập
            FR-01: Người dùng đăng nhập bằng email và mật khẩu.
            FR-02: Sai mật khẩu 5 lần thì khóa tài khoản 15 phút.

            2.2 Giỏ hàng
            FR-03: Người dùng thêm sản phẩm vào giỏ, số lượng tối đa 10.
            FR-04: Áp dụng mã giảm giá còn hạn.
            """;

    @Test
    void parsesUtf8TextAndSplitsIntoSections() {
        SrsService.SrsDocument doc = service.parse("srs.txt", SRS_TXT.getBytes(StandardCharsets.UTF_8));
        assertEquals("txt", doc.format());
        // Dòng mục lục không bị coi là tiêu đề
        assertTrue(doc.sections().stream().noneMatch(sec -> sec.title().contains("........")));
        assertFalse(doc.truncated());
        assertFalse(doc.sections().isEmpty());
        String all = String.join("\n", doc.sections().stream().map(s -> s.title() + "\n" + s.content()).toList());
        assertTrue(all.contains("FR-01"));
        assertTrue(all.contains("FR-04"));
        // Mục lục được đánh dấu "có thể không chứa yêu cầu"
        assertTrue(doc.sections().stream().anyMatch(s -> s.title().contains("Mục lục") && !s.suggested()));
        // Các phần chứa yêu cầu được gợi ý chọn
        assertTrue(doc.sections().stream().filter(s -> s.content().contains("FR-03")).allMatch(SrsService.Section::suggested));
    }

    @Test
    void decodesBomAndWindows1258() {
        byte[] bom = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] body = "Đăng nhập".getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, withBom, 0, 3);
        System.arraycopy(body, 0, withBom, 3, body.length);
        assertEquals("Đăng nhập", SrsService.decodeText(withBom));

        // File Notepad cũ lưu bằng bảng mã Windows tiếng Việt
        // (chỉ dùng ký tự có sẵn trong bảng mã: Đ = 0xD0, ă = 0xE3 -> không phải UTF-8 hợp lệ)
        byte[] cp1258 = "Đăng nhap".getBytes(Charset.forName("windows-1258"));
        assertEquals((byte) 0xD0, cp1258[0]);
        assertEquals("Đăng nhap", SrsService.decodeText(cp1258));
    }

    @Test
    void longSectionIsChunked() {
        StringBuilder sb = new StringBuilder("# Yêu cầu\n\n");
        for (int i = 1; i <= 400; i++) sb.append("FR-").append(i).append(": Hệ thống phải xử lý yêu cầu số ").append(i).append(" đúng hạn.\n\n");
        SrsService.SrsDocument doc = service.parse("big.md", sb.toString().getBytes(StandardCharsets.UTF_8));
        assertTrue(doc.sections().size() > 1);
        assertTrue(doc.sections().stream().allMatch(s -> s.chars() <= SrsService.MAX_CHUNK));
        assertTrue(doc.sections().get(0).title().startsWith("Yêu cầu ("));
    }

    @Test
    void readsDocxHeadingsListsAndTables() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFParagraph h = doc.createParagraph();
            h.setStyle("Heading1");
            h.createRun().setText("Quản lý tài khoản");
            doc.createParagraph().createRun().setText("FR-10: Admin có thể khóa tài khoản người dùng.");
            XWPFTable table = doc.createTable(2, 2);
            table.getRow(0).getCell(0).setText("Mã");
            table.getRow(0).getCell(1).setText("Mô tả");
            table.getRow(1).getCell(0).setText("FR-11");
            table.getRow(1).getCell(1).setText("Đặt lại mật khẩu");
            doc.write(out);
        }
        String text = SrsService.readDocx(out.toByteArray());
        assertTrue(text.contains("FR-10"));
        assertTrue(text.contains("| FR-11 | Đặt lại mật khẩu |"));

        SrsService.SrsDocument parsed = service.parse("srs.docx", out.toByteArray());
        assertEquals("docx", parsed.format());
        assertTrue(parsed.sections().stream().anyMatch(s -> s.content().contains("FR-11")));
    }

    @Test
    void readsPdfText() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PDDocument pdf = new PDDocument()) {
            PDPage page = new PDPage();
            pdf.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(pdf, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 700);
                cs.showText("FR-20: The system shall export reports to PDF.");
                cs.endText();
            }
            pdf.save(out);
        }
        SrsService.SrsDocument doc = service.parse("srs.pdf", out.toByteArray());
        assertEquals(1, doc.pages());
        assertTrue(doc.sections().get(0).content().contains("FR-20"));
    }

    @Test
    void rejectsUnsupportedAndEmptyFiles() {
        assertThrows(SrsService.SrsException.class, () -> service.parse("srs.exe", "abc".getBytes()));
        assertThrows(SrsService.SrsException.class, () -> service.parse("srs.txt", new byte[0]));
        assertThrows(SrsService.SrsException.class, () -> service.parse("srs.docx", "not a zip".getBytes()));
        SrsService.SrsException e = assertThrows(SrsService.SrsException.class, () -> service.parse("x.txt", "   \n  ".getBytes()));
        assertEquals("no_text", e.getCode());
    }

    @Test
    void headingDetectionIgnoresSentences() {
        assertNotNull(SrsService.headingTitle("2.1 Đăng nhập"));
        assertNotNull(SrsService.headingTitle("## Giỏ hàng"));
        assertNotNull(SrsService.headingTitle("Chương 3 Yêu cầu phi chức năng"));
        assertNull(SrsService.headingTitle("1. Người dùng nhập email và bấm đăng nhập."));
        assertNull(SrsService.headingTitle("#### Tiêu đề cấp 4"));
        assertNull(SrsService.headingTitle("2.1 Đăng nhập ........ 5"));
        assertNotNull(SrsService.headingTitle("Mục lục"));
        List<String> parts = SrsService.chunk("a".repeat(5000) + "\n\n" + "b".repeat(5000));
        assertEquals(2, parts.size());
    }
}
