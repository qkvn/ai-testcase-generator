package com.example.demo;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Đọc tài liệu SRS (.txt, .md, .docx, .pdf) thành văn bản và chia thành các phần (theo tiêu đề / số mục)
 * để AI sinh test case lần lượt từng phần.
 */
@Service
public class SrsService {

    public static final long MAX_FILE_BYTES = 5L * 1024 * 1024;
    public static final int MAX_CHARS = 200_000;
    /** Kích thước mong muốn của một phần gửi cho AI (ký tự). */
    static final int TARGET_CHUNK = 6_000;
    /** Phần lớn hơn mức này sẽ bị cắt nhỏ. */
    static final int MAX_CHUNK = 9_000;
    /** Phần gần như trống (VD: tiêu đề + 1 câu giới thiệu) được gộp với phần kế tiếp. */
    static final int SMALL_SECTION = 300;

    private static final Pattern MD_HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*#*\\s*$");
    private static final Pattern NUM_HEADING = Pattern.compile("^(\\d{1,2}(?:\\.\\d{1,2}){0,3})\\.?\\s+(\\S.{1,110})$");
    private static final Pattern WORD_HEADING = Pattern.compile("^(?i)(chương|phần|mục|chapter|section|part)\\s+([0-9]+|[IVXLC]+)\\b.{0,100}$");
    /** Dòng mục lục có dấu chấm dẫn và số trang, VD: "2.1 Đăng nhập ........ 5" -> không phải tiêu đề. */
    private static final Pattern TOC_LINE = Pattern.compile(".*(\\.{3,}|…{2,}|_{3,})\\s*\\d+\\s*$");
    /** Tiêu đề đứng riêng một dòng không có số (VD: "Mục lục", "Phụ lục A"). */
    private static final Pattern PLAIN_SPECIAL_HEADING = Pattern.compile(
            "^(?i)(mục lục|table of contents|contents|lịch sử (sửa đổi|thay đổi|phiên bản)|revision history|version history|"
                    + "tài liệu tham khảo|references|thuật ngữ.{0,40}|từ viết tắt.{0,40}|glossary|definitions.{0,40}|"
                    + "phụ lục.{0,60}|appendix.{0,60}|yêu cầu chức năng|yêu cầu phi chức năng|functional requirements|non-functional requirements)$");
    private static final Pattern NON_REQUIREMENT_TITLE = Pattern.compile(
            "(?i)(mục lục|table of contents|contents|lịch sử (sửa đổi|thay đổi|phiên bản)|revision history|version history|"
                    + "tài liệu tham khảo|references|thuật ngữ|từ viết tắt|glossary|definitions|acronyms|phụ lục|appendix)");

    public record Section(int index, String title, String content, int chars, boolean suggested) {}

    public record SrsDocument(String fileName, String format, int chars, Integer pages, boolean truncated, List<Section> sections) {}

    /** Lỗi đọc tài liệu kèm thông báo cho người dùng. */
    public static class SrsException extends RuntimeException {
        private final String code;
        public SrsException(String code, String message) { super(message); this.code = code; }
        public String getCode() { return code; }
    }

    // ===================================================================
    // ĐỌC FILE
    // ===================================================================

    public SrsDocument parse(String fileName, byte[] bytes) {
        if (bytes == null || bytes.length == 0) throw new SrsException("empty_file", "File rỗng");
        if (bytes.length > MAX_FILE_BYTES) throw new SrsException("too_large", "File vượt quá 5 MB");

        String name = fileName == null ? "srs.txt" : fileName.replaceAll("[\\\\/]+", "_").trim();
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        String text;
        Integer pages = null;
        switch (ext) {
            case "txt", "md", "markdown" -> text = decodeText(bytes);
            case "docx" -> text = readDocx(bytes);
            case "pdf" -> {
                PdfResult r = readPdf(bytes);
                text = r.text();
                pages = r.pages();
            }
            default -> throw new SrsException("bad_format", "Chỉ hỗ trợ file .txt, .md, .docx, .pdf");
        }

        text = normalize(text);
        if (text.isBlank()) {
            throw new SrsException("no_text", "pdf".equals(ext)
                    ? "Không đọc được chữ trong file PDF (có thể là PDF dạng ảnh scan)"
                    : "Tài liệu không có nội dung chữ");
        }
        boolean truncated = text.length() > MAX_CHARS;
        if (truncated) text = text.substring(0, MAX_CHARS);
        if (pages == null) pages = Math.max(1, (int) Math.ceil(text.length() / 3000.0)); // ước tính ~3000 ký tự / trang

        return new SrsDocument(name, ext, text.length(), pages, truncated, split(text));
    }

    /** Giải mã file văn bản: UTF-8 (có / không BOM), UTF-16, nếu lỗi thì thử bảng mã Windows tiếng Việt. */
    static String decodeText(byte[] b) {
        if (b.length >= 3 && b[0] == (byte) 0xEF && b[1] == (byte) 0xBB && b[2] == (byte) 0xBF) {
            return new String(b, 3, b.length - 3, StandardCharsets.UTF_8);
        }
        if (b.length >= 2 && b[0] == (byte) 0xFF && b[1] == (byte) 0xFE) {
            return new String(b, 2, b.length - 2, StandardCharsets.UTF_16LE);
        }
        if (b.length >= 2 && b[0] == (byte) 0xFE && b[1] == (byte) 0xFF) {
            return new String(b, 2, b.length - 2, StandardCharsets.UTF_16BE);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(b)).toString();
        } catch (CharacterCodingException e) {
            try {
                return new String(b, Charset.forName("windows-1258"));
            } catch (Exception ex) {
                return new String(b, StandardCharsets.ISO_8859_1);
            }
        }
    }

    /** Đọc .docx: giữ thứ tự đoạn văn và bảng; tiêu đề chuyển thành "# ..." để nhận diện các phần. */
    static String readDocx(byte[] bytes) {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            StringBuilder sb = new StringBuilder();
            for (IBodyElement el : doc.getBodyElements()) {
                if (el instanceof XWPFParagraph p) {
                    String t = p.getText() == null ? "" : p.getText().trim();
                    if (t.isEmpty()) continue;
                    int level = headingLevel(doc, p);
                    if (level > 0) {
                        sb.append('\n').append("#".repeat(Math.min(level, 6))).append(' ').append(t.replace('\n', ' ')).append("\n\n");
                    } else if (p.getNumID() != null) {
                        sb.append("- ").append(t).append('\n');
                    } else {
                        sb.append(t).append("\n\n");
                    }
                } else if (el instanceof XWPFTable table) {
                    for (XWPFTableRow row : table.getRows()) {
                        List<String> cells = new ArrayList<>();
                        for (XWPFTableCell c : row.getTableCells()) {
                            cells.add(c.getText() == null ? "" : c.getText().replaceAll("\\s+", " ").trim());
                        }
                        if (cells.stream().anyMatch(c -> !c.isEmpty())) {
                            sb.append("| ").append(String.join(" | ", cells)).append(" |\n");
                        }
                    }
                    sb.append('\n');
                }
            }
            return sb.toString();
        } catch (Exception e) {
            throw new SrsException("bad_docx", "Không đọc được file Word (.docx). Hãy kiểm tra file không bị hỏng hoặc đặt mật khẩu");
        }
    }

    /** Cấp tiêu đề của đoạn văn trong Word (0 = không phải tiêu đề). */
    private static int headingLevel(XWPFDocument doc, XWPFParagraph p) {
        String styleId = p.getStyle();
        if (styleId == null) return 0;
        String name = styleId;
        try {
            XWPFStyles styles = doc.getStyles();
            XWPFStyle style = styles == null ? null : styles.getStyle(styleId);
            if (style != null && style.getName() != null) name = style.getName();
        } catch (Exception ignored) { }
        String n = name.toLowerCase(Locale.ROOT);
        if (n.equals("title") || n.equals("tiêu đề tài liệu")) return 1;
        Matcher m = Pattern.compile("(heading|tiêu đề|đầu đề)\\s*(\\d)").matcher(n.replace("_", " "));
        if (m.find()) return Integer.parseInt(m.group(2));
        return 0;
    }

    private record PdfResult(String text, int pages) {}

    static PdfResult readPdf(byte[] bytes) {
        try (PDDocument pdf = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return new PdfResult(stripper.getText(pdf), pdf.getNumberOfPages());
        } catch (InvalidPasswordException e) {
            throw new SrsException("pdf_password", "File PDF đang được đặt mật khẩu, hãy bỏ mật khẩu rồi tải lên lại");
        } catch (Exception e) {
            throw new SrsException("bad_pdf", "Không đọc được file PDF. Hãy kiểm tra file không bị hỏng");
        }
    }

    static String normalize(String text) {
        String t = Normalizer.normalize(text, Normalizer.Form.NFC)
                .replace("\r\n", "\n").replace('\r', '\n')
                .replace(' ', ' ').replace("\t", "    ");
        StringBuilder sb = new StringBuilder();
        int blank = 0;
        for (String line : t.split("\n", -1)) {
            String l = line.stripTrailing();
            if (l.isBlank()) {
                if (++blank > 1) continue;
                sb.append('\n');
            } else {
                blank = 0;
                sb.append(l).append('\n');
            }
        }
        return sb.toString().trim();
    }

    // ===================================================================
    // CHIA PHẦN
    // ===================================================================

    private static final class Raw {
        String title;
        final StringBuilder body = new StringBuilder();
        Raw(String title) { this.title = title; }
        int chars() { return body.toString().trim().length(); }
    }

    /** Dòng tiêu đề cấp 1–3 thì trả về tiêu đề, ngược lại null. */
    static String headingTitle(String line) {
        String l = line.trim();
        if (l.isEmpty() || l.length() > 120) return null;
        if (TOC_LINE.matcher(l).matches()) return null;
        if (PLAIN_SPECIAL_HEADING.matcher(l).matches()) return l;
        Matcher md = MD_HEADING.matcher(l);
        if (md.matches()) return md.group(1).length() <= 3 ? md.group(2).trim() : null;
        Matcher num = NUM_HEADING.matcher(l);
        if (num.matches()) {
            int level = num.group(1).split("\\.").length;
            String rest = num.group(2);
            // Loại câu văn thường bắt đầu bằng số (kết thúc bằng dấu câu, hoặc không có chữ cái)
            if (level <= 3 && !rest.matches(".*[.;:,]$") && rest.matches(".*\\p{L}.*")) return l;
            return null;
        }
        if (WORD_HEADING.matcher(l).matches()) return l;
        return null;
    }

    static List<Section> split(String text) {
        List<Raw> raws = new ArrayList<>();
        Raw cur = new Raw(null);
        for (String line : text.split("\n", -1)) {
            String title = headingTitle(line);
            if (title != null) {
                if (cur.chars() > 0 || cur.title != null) raws.add(cur);
                cur = new Raw(title);
            } else {
                cur.body.append(line).append('\n');
            }
        }
        raws.add(cur);
        raws.removeIf(r -> r.chars() == 0 && r.title == null);

        // Gộp tiêu đề rỗng / phần quá nhỏ với phần kế tiếp (giảm số lượt gọi AI)
        List<Raw> merged = new ArrayList<>();
        for (Raw r : raws) {
            Raw last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if (last != null && last.chars() < SMALL_SECTION && last.chars() + r.chars() <= TARGET_CHUNK
                    && isRequirementLike(last.title) == isRequirementLike(r.title)) {
                if (r.title != null) {
                    last.body.append('\n').append(last.title == null ? "" : "### ").append(r.title).append('\n');
                    last.title = last.title == null ? r.title : joinTitles(last.title, r.title);
                }
                last.body.append(r.body);
            } else {
                merged.add(r);
            }
        }

        // Cắt phần quá lớn theo ranh giới đoạn văn
        List<Section> out = new ArrayList<>();
        int partNo = 0;
        for (Raw r : merged) {
            String body = r.body.toString().trim();
            if (body.isEmpty()) continue;
            String baseTitle = r.title != null ? r.title : null;
            List<String> pieces = body.length() > MAX_CHUNK ? chunk(body) : List.of(body);
            for (int i = 0; i < pieces.size(); i++) {
                String title = baseTitle != null ? baseTitle : "Phần " + (++partNo);
                if (pieces.size() > 1) title = title + " (" + (i + 1) + "/" + pieces.size() + ")";
                String content = pieces.get(i);
                out.add(new Section(out.size(), title, content, content.length(), isRequirementLike(baseTitle)));
            }
        }
        return out;
    }

    private static boolean isRequirementLike(String title) {
        return title == null || !NON_REQUIREMENT_TITLE.matcher(title).find();
    }

    private static String joinTitles(String a, String b) {
        String t = a + " · " + b;
        return t.length() > 120 ? t.substring(0, 117) + "..." : t;
    }

    /** Cắt văn bản dài thành các đoạn ~TARGET_CHUNK ký tự, ưu tiên cắt ở dòng trống / xuống dòng. */
    static List<String> chunk(String body) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        while (start < body.length()) {
            int end = Math.min(body.length(), start + TARGET_CHUNK);
            if (end < body.length()) {
                int cut = body.lastIndexOf("\n\n", end);
                if (cut <= start + TARGET_CHUNK / 2) cut = body.lastIndexOf('\n', end);
                if (cut <= start + TARGET_CHUNK / 2) cut = body.lastIndexOf(' ', end);
                if (cut > start) end = cut;
            }
            String piece = body.substring(start, end).trim();
            if (!piece.isEmpty()) parts.add(piece);
            start = end;
        }
        return parts;
    }
}
