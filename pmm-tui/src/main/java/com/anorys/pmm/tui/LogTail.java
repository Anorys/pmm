package com.anorys.pmm.tui;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The end of a Maven log, and where the reactor is: "[INFO] Building core-api 3.1.5 [2/7]". */
final class LogTail {

    record Progress(String module, int index, int count, List<String> lines) {
    }

    static final int MAX_BYTES = 32 * 1024;
    static final int MAX_LINES = 200;

    private static final Pattern BUILDING = Pattern.compile("^\\[INFO] Building (\\S+)(?: \\S+)?(?:\\s+\\[(\\d+)/(\\d+)])?\\s*$");

    private LogTail() {
    }

    /** The last lines of {@code log}, or {@code null} when it cannot be read yet. */
    static Progress read(Path log) {
        if (!Files.isRegularFile(log)) {
            return null;
        }
        String text;
        try (RandomAccessFile file = new RandomAccessFile(log.toFile(), "r")) {
            long length = file.length();
            long start = Math.max(0, length - MAX_BYTES);
            byte[] bytes = new byte[(int) (length - start)];
            file.seek(start);
            file.readFully(bytes);
            text = new String(bytes, StandardCharsets.UTF_8);
            if (start > 0) {
                int newline = text.indexOf('\n');
                text = newline < 0 ? "" : text.substring(newline + 1);
            }
        } catch (IOException e) {
            return null;
        }
        return parse(text);
    }

    static Progress parse(String text) {
        List<String> all = new ArrayList<>(Arrays.asList(text.replace("\r", "").split("\n", -1)));
        if (!all.isEmpty() && all.get(all.size() - 1).isEmpty()) {
            all.remove(all.size() - 1);
        }
        List<String> lines = new ArrayList<>(all.subList(Math.max(0, all.size() - MAX_LINES), all.size()));
        for (int i = lines.size() - 1; i >= 0; i--) {
            Matcher m = BUILDING.matcher(lines.get(i));
            if (m.matches()) {
                return m.group(2) == null
                        ? new Progress(m.group(1), 0, 0, lines)
                        : new Progress(m.group(1), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)), lines);
            }
        }
        return new Progress(null, 0, 0, lines);
    }
}
