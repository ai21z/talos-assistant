package dev.talos.engine.llamacpp;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

final class GgufMetadata {
    private static final int GGUF_STRING = 8;
    private static final int GGUF_ARRAY = 9;
    private static final long MAX_METADATA_ITEMS = 4096;
    private static final long MAX_STRING_BYTES = 1024 * 1024;

    private GgufMetadata() {}

    static Optional<String> architecture(Path path) {
        if (path == null || !Files.isRegularFile(path)) return Optional.empty();
        try (InputStream in = Files.newInputStream(path)) {
            byte[] magic = in.readNBytes(4);
            if (magic.length != 4
                    || magic[0] != 'G'
                    || magic[1] != 'G'
                    || magic[2] != 'U'
                    || magic[3] != 'F') {
                return Optional.empty();
            }
            readUInt32(in); // version
            readUInt64(in); // tensor_count
            long metadataCount = Math.min(readUInt64(in), MAX_METADATA_ITEMS);

            for (long i = 0; i < metadataCount; i++) {
                String key = readString(in);
                int type = (int) readUInt32(in);
                if ("general.architecture".equals(key) && type == GGUF_STRING) {
                    String architecture = readString(in).trim();
                    return architecture.isBlank() ? Optional.empty() : Optional.of(architecture);
                }
                skipValue(in, type);
            }
        } catch (Exception ignored) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    private static void skipValue(InputStream in, int type) throws IOException {
        switch (type) {
            case 0, 1, 7 -> skipFully(in, 1);
            case 2, 3 -> skipFully(in, 2);
            case 4, 5, 6 -> skipFully(in, 4);
            case GGUF_STRING -> readString(in);
            case GGUF_ARRAY -> {
                int elementType = (int) readUInt32(in);
                long count = readUInt64(in);
                for (long i = 0; i < count; i++) {
                    skipValue(in, elementType);
                }
            }
            case 10, 11, 12 -> skipFully(in, 8);
            default -> throw new IOException("unsupported GGUF metadata type " + type);
        }
    }

    private static String readString(InputStream in) throws IOException {
        long length = readUInt64(in);
        if (length < 0 || length > MAX_STRING_BYTES) {
            throw new IOException("invalid GGUF string length " + length);
        }
        byte[] bytes = in.readNBytes((int) length);
        if (bytes.length != (int) length) throw new EOFException();
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static long readUInt32(InputStream in) throws IOException {
        byte[] bytes = in.readNBytes(4);
        if (bytes.length != 4) throw new EOFException();
        return ((long) bytes[0] & 0xff)
                | (((long) bytes[1] & 0xff) << 8)
                | (((long) bytes[2] & 0xff) << 16)
                | (((long) bytes[3] & 0xff) << 24);
    }

    private static long readUInt64(InputStream in) throws IOException {
        byte[] bytes = in.readNBytes(8);
        if (bytes.length != 8) throw new EOFException();
        return ((long) bytes[0] & 0xff)
                | (((long) bytes[1] & 0xff) << 8)
                | (((long) bytes[2] & 0xff) << 16)
                | (((long) bytes[3] & 0xff) << 24)
                | (((long) bytes[4] & 0xff) << 32)
                | (((long) bytes[5] & 0xff) << 40)
                | (((long) bytes[6] & 0xff) << 48)
                | (((long) bytes[7] & 0xff) << 56);
    }

    private static void skipFully(InputStream in, long bytes) throws IOException {
        long remaining = bytes;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                if (in.read() < 0) throw new EOFException();
                skipped = 1;
            }
            remaining -= skipped;
        }
    }
}
