package dev.loqj.core.ingest;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.jsoup.Jsoup;

import java.io.*;
import java.nio.file.*;
import java.util.stream.Collectors;

public class ParserUtil {

    public static String readTextFile(Path p) throws IOException {
        return Files.readString(p);
    }

    public static String parseHtml(Path p) throws IOException {
        try (InputStream in = Files.newInputStream(p)) {
            // charset null => jsoup will sniff from meta/http headers
            return Jsoup.parse(in, null, "").text();
        }
    }

    public static String parsePdf(Path p) throws IOException {
        // PDFBox 3.x: use Loader.loadPDF(File|InputStream)
        try (PDDocument doc = Loader.loadPDF(p.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(doc);
        }
    }

    public static String parseDocx(Path p) throws IOException {
        try (InputStream in = Files.newInputStream(p);
             XWPFDocument doc = new XWPFDocument(in)) {
            return doc.getParagraphs()
                    .stream()
                    .map(par -> par.getText())
                    .collect(Collectors.joining("\n"));
        }
    }

    public static String smartParse(Path p) throws IOException {
        String name = p.getFileName().toString().toLowerCase();
        if (name.endsWith(".pdf"))  return parsePdf(p);
        if (name.endsWith(".html") || name.endsWith(".htm")) return parseHtml(p);
        if (name.endsWith(".docx")) return parseDocx(p);
        // fallback: code/markdown/txt/json/yaml…
        return readTextFile(p);
    }
}
