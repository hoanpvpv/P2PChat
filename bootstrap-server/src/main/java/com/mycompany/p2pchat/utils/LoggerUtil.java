package com.mycompany.p2pchat.utils;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.ConsoleHandler;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

public class LoggerUtil {

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    public static Logger getLogger(String name) {
        Logger logger = Logger.getLogger(name);
        logger.setUseParentHandlers(false);

        ConsoleHandler handler = new ConsoleHandler();
        handler.setFormatter(new Formatter() {
            @Override
            public String format(LogRecord record) {
                String time = LocalDateTime.now().format(FORMATTER);
                return String.format("[%s] [%s] %s%n",
                        time, record.getLevel().getName(), record.getMessage());
            }
        });
        handler.setLevel(Level.ALL);

        if (logger.getHandlers().length == 0) {
            logger.addHandler(handler);
        }
        logger.setLevel(Level.ALL);
        return logger;
    }

    public static String getStackTrace(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
}
