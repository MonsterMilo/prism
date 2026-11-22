package prism.core;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.Date;

import prism.api.Logger;

/** Log file at logs/prism.log (previous run kept as prism.old.log). */
final class CoreLog {
    private static Writer out;
    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm:ss");

    static synchronized void open(Path logsDir) {
        try {
            Files.createDirectories(logsDir);
            Path file = logsDir.resolve("prism.log");
            if (Files.exists(file)) Files.move(file, logsDir.resolve("prism.old.log"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            out = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            out = null;
        }
    }

    static synchronized void close() {
        if (out == null) return;
        try {
            out.close();
        } catch (IOException ignored) {
        }
        out = null;
    }

    static synchronized void write(String level, String source, String msg, Throwable t) {
        StringBuilder line = new StringBuilder()
                .append(TIME.format(new Date())).append(' ').append(level)
                .append(" [").append(source).append("] ").append(msg);
        if (t != null) {
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            line.append('\n').append(sw.toString().trim());
        }
        String s = line.toString();
        if (out != null) {
            try {
                out.write(s);
                out.write('\n');
                out.flush();
            } catch (IOException ignored) {
            }
        }
    }

    static Logger forSource(String source) {
        return new Logger() {
            public void info(String msg) { write("INFO", source, msg, null); }
            public void warn(String msg) { write("WARN", source, msg, null); }
            public void error(String msg, Throwable t) { write("ERROR", source, msg, t); }
        };
    }
}
