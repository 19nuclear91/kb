package com.zhishiku.kb.common.file;

import org.w3c.dom.Element;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.Reader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipInputStream;

/**
 * 上传与 Worker 共用的内容校验；不信任客户端 MIME，不解压到文件系统。
 */
public final class DocumentFilePolicy {
    public static final long MAX_EXPANDED_BYTES = 100L * 1024 * 1024;
    public static final int MAX_ZIP_ENTRIES = 2000;
    private static final byte[] OLE = {(byte) 0xd0, (byte) 0xcf, 0x11, (byte) 0xe0,
            (byte) 0xa1, (byte) 0xb1, 0x1a, (byte) 0xe1};
    private static final Map<String, String> TYPES = Map.of(
            "pdf", "application/pdf", "txt", "text/plain", "md", "text/plain",
            "doc", "application/msword", "xls", "application/vnd.ms-excel",
            "ppt", "application/vnd.ms-powerpoint",
            "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation");

    private DocumentFilePolicy() {
    }

    public static String inspect(InputStream source, String fileName, long maxBytes) throws IOException {
        String extension = extension(fileName);
        String mime = TYPES.get(extension);
        if (mime == null) throw rejected("只支持 PDF、TXT、Markdown 和常规 Office 文档");
        var input = new BufferedInputStream(new LimitedInputStream(source, maxBytes));
        input.mark(16);
        byte[] header = input.readNBytes(8);
        input.reset();
        if (header.length == 0) throw rejected("文件内容为空");
        switch (extension) {
            case "pdf" -> {
                if (!startsWith(header, "%PDF-".getBytes(StandardCharsets.US_ASCII)))
                    throw rejected("PDF 文件签名不匹配");
                drain(input);
            }
            case "doc", "xls", "ppt" -> {
                if (!startsWith(header, OLE)) throw rejected("Office 文件签名不匹配");
                drain(input);
            }
            case "docx", "xlsx", "pptx" -> inspectOfficeArchive(input, extension);
            default -> {
                // 文本统一接收 UTF-8，避免二进制伪装成可预览文本。
                var decoder = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT);
                Reader reader = new InputStreamReader(input, decoder);
                char[] buffer = new char[8192];
                for (int n; (n = reader.read(buffer)) != -1; ) {
                    for (int i = 0; i < n; i++) {
                        char ch = buffer[i];
                        if (Character.isISOControl(ch) && ch != '\n' && ch != '\r' && ch != '\t')
                            throw rejected("文本包含二进制控制字符");
                    }
                }
            }
        }
        return mime;
    }

    private static void inspectOfficeArchive(InputStream input, String extension) throws IOException {
        String mainPart = switch (extension) {
            case "docx" -> "word/document.xml";
            case "xlsx" -> "xl/workbook.xml";
            default -> "ppt/presentation.xml";
        };
        String mainContentType = switch (extension) {
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml";
            default -> "application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml";
        };
        boolean mainFound = false;
        boolean typeFound = false;
        long expanded = 0;
        int count = 0;
        Set<String> names = new HashSet<>();
        var zip = new ZipInputStream(input);
        for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
            String name = entry.getName();
            String lower = name.toLowerCase(Locale.ROOT);
            if (++count > MAX_ZIP_ENTRIES || !names.add(name)) throw rejected("压缩包条目过多或重复");
            if (name.contains("..") || name.startsWith("/") || name.contains("\\")
                    || lower.contains("vbaproject") || lower.contains("/embeddings/"))
                throw rejected("文档含不允许的路径、宏或嵌入附件");
            boolean metadata = name.equals("[Content_Types].xml") || lower.endsWith(".rels");
            var bytes = metadata ? new ByteArrayOutputStream() : null;
            byte[] buffer = new byte[8192];
            for (int n; (n = zip.read(buffer)) != -1; ) {
                expanded += n;
                if (expanded > MAX_EXPANDED_BYTES) throw rejected("文档解压大小超过限制");
                if (metadata) {
                    if (bytes.size() + n > 1024 * 1024) throw rejected("文档关系元数据过大");
                    bytes.write(buffer, 0, n);
                }
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("文档检查已取消");
            }
            if (name.equals(mainPart)) mainFound = true;
            if (metadata) {
                Element root = secureXml(bytes.toByteArray());
                if (name.equals("[Content_Types].xml")) {
                    var types = root.getElementsByTagNameNS("*", "Override");
                    for (int i = 0; i < types.getLength(); i++) {
                        Element type = (Element) types.item(i);
                        String value = type.getAttribute("ContentType");
                        if (value.toLowerCase(Locale.ROOT).contains("macro")
                                || value.toLowerCase(Locale.ROOT).contains("vba"))
                            throw rejected("不支持带宏文档");
                        if (("/" + mainPart).equals(type.getAttribute("PartName"))
                                && mainContentType.equals(value)) typeFound = true;
                    }
                } else {
                    var relationships = root.getElementsByTagNameNS("*", "Relationship");
                    for (int i = 0; i < relationships.getLength(); i++) {
                        Element relationship = (Element) relationships.item(i);
                        // 普通超链接仅作为文字保留；外部图片、模板等依赖不进入解析器。
                        if ("External".equalsIgnoreCase(relationship.getAttribute("TargetMode"))
                                && !relationship.getAttribute("Type").endsWith("/hyperlink"))
                            throw rejected("文档包含外部资源引用");
                    }
                }
            }
        }
        drain(input);
        if (!mainFound || !typeFound) throw rejected("Office 内容类型与扩展名不匹配");
    }

    private static Element secureXml(byte[] data) {
        try {
            var factory = DocumentBuilderFactory.newDefaultInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler() {
                @Override
                public void fatalError(SAXParseException e) throws SAXException {
                    throw e;
                }
            });
            return builder.parse(new ByteArrayInputStream(data)).getDocumentElement();
        } catch (Exception e) {
            throw rejected("文档 XML 元数据无效或包含外部实体");
        }
    }

    /**
     * 预览只返回纯文本或带 PDF 签名的文件；旧记录里的 MIME 也不能直接信任。
     */
    public static String previewMime(String fileName, String mime, byte[] header) {
        String ext = extension(fileName);
        if (Set.of("txt", "md").contains(ext)
                && Set.of("text/plain", "text/markdown").contains(mime == null ? "" : mime))
            return "text/plain; charset=UTF-8";
        if (ext.equals("pdf") && "application/pdf".equals(mime)
                && startsWith(header, "%PDF-".getBytes(StandardCharsets.US_ASCII)))
            return "application/pdf";
        throw rejected("该格式暂不支持安全在线预览，请通过下载接口获取原件");
    }

    private static String extension(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (bytes[i] != prefix[i]) return false;
        return true;
    }

    private static void drain(InputStream input) throws IOException {
        byte[] buffer = new byte[8192];
        while (input.read(buffer) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("文档检查已取消");
        }
    }

    private static IllegalArgumentException rejected(String message) {
        return new IllegalArgumentException(message);
    }

    /**
     * 即使对象存储或上传元数据谎报长度，也在实际读取时施加上限。
     */
    public static final class LimitedInputStream extends FilterInputStream {
        private final long limit;
        private long count;

        public LimitedInputStream(InputStream input, long limit) {
            super(input);
            if (limit <= 0) throw rejected("文件字节上限必须为正数");
            this.limit = limit;
        }

        private void add(long n) throws IOException {
            count += n;
            if (count > limit) throw new IOException("文件实际大小超过限制");
        }

        @Override
        public int read() throws IOException {
            int result = in.read();
            if (result != -1) add(1);
            return result;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = in.read(b, off, (int) Math.min(len, Math.min(Integer.MAX_VALUE, limit - count + 1)));
            if (n > 0) add(n);
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            long skipped = in.skip(Math.min(n, limit - count + 1));
            add(skipped);
            return skipped;
        }
    }
}
