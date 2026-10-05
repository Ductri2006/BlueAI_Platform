package com.blueai.rag.document.service;

import com.blueai.auth.exception.ApiException;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipFile;

/** Bounded format checks before a document is queued; not an antivirus or full parser. */
final class DocumentContentValidator {
    private static final long MAX_EXPANDED_BYTES = 64L * 1024 * 1024;
    private static final long MAX_ENTRY_BYTES = 16L * 1024 * 1024;
    private static final int MAX_ENTRIES = 2048;

    private DocumentContentValidator() { }

    static void validate(Path file, String extension) throws IOException {
        switch (extension) {
            case ".pdf" -> validatePdf(file);
            case ".docx" -> validateDocx(file);
            default -> validateText(file);
        }
    }

    private static void validatePdf(Path file) throws IOException {
        try (var input = Files.newInputStream(file)) {
            String header = new String(input.readNBytes(8), StandardCharsets.US_ASCII);
            if (!header.matches("%PDF-[12]\\.[0-9]")) {
                throw invalid("The PDF signature is invalid.");
            }
        }
    }

    private static void validateText(Path file) throws IOException {
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try (var reader = new InputStreamReader(Files.newInputStream(file), decoder)) {
            char[] buffer = new char[8192];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                for (int i = 0; i < read; i++) {
                    char value = buffer[i];
                    if (Character.isISOControl(value) && value != '\n' && value != '\r' && value != '\t') {
                        throw invalid("Text documents must be UTF-8 text without binary control characters.");
                    }
                }
            }
        } catch (java.nio.charset.CharacterCodingException exception) {
            throw invalid("Text documents must be valid UTF-8.");
        }
    }

    private static void validateDocx(Path file) throws IOException {
        Set<String> names = new HashSet<>();
        long totalBytes = 0;
        try (var zip = new ZipFile(file.toFile())) {
            var entries = zip.entries();
            byte[] buffer = new byte[8192];
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                String name = entry.getName();
                if (!names.add(name) || names.size() > MAX_ENTRIES || !safeEntryName(name)) {
                    throw invalid("The DOCX archive contains invalid entries.");
                }
                String lowerName = name.toLowerCase(java.util.Locale.ROOT);
                if (lowerName.endsWith("vbaproject.bin") || lowerName.startsWith("word/embeddings/")) {
                    throw invalid("Macros and embedded objects are not supported in knowledge-base DOCX files.");
                }
                if (entry.getSize() > MAX_ENTRY_BYTES || entry.getSize() < 0) {
                    throw invalid("The DOCX archive exceeds safe extraction limits.");
                }
                if (entry.isDirectory()) { continue; }
                long entryBytes = 0;
                try (var input = zip.getInputStream(entry)) {
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        entryBytes += read;
                        totalBytes += read;
                        if (entryBytes > MAX_ENTRY_BYTES || totalBytes > MAX_EXPANDED_BYTES) {
                            throw invalid("The DOCX archive exceeds safe extraction limits.");
                        }
                    }
                }
                if (entryBytes != entry.getSize()) {
                    throw invalid("The DOCX archive contains inconsistent entries.");
                }
            }
            if (!names.contains("[Content_Types].xml") || !names.contains("_rels/.rels")
                    || !names.contains("word/document.xml")) {
                throw invalid("The upload is not a DOCX document package.");
            }
        } catch (java.util.zip.ZipException exception) {
            throw invalid("The DOCX archive is malformed.");
        }
    }

    private static boolean safeEntryName(String name) {
        if (name.isBlank() || name.startsWith("/") || name.contains("\\") || name.contains(":")) {
            return false;
        }
        for (String segment : name.split("/")) {
            if (segment.equals("..") || segment.equals(".")) { return false; }
        }
        return name.chars().noneMatch(Character::isISOControl);
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_FILE_CONTENT", message);
    }
}
