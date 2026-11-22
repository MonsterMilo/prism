package prism.api;

/** Writes to Prism's log file (logs/prism.log). */
public interface Logger {
    void info(String msg);

    void warn(String msg);

    void error(String msg, Throwable t);

    default void error(String msg) { error(msg, null); }
}
