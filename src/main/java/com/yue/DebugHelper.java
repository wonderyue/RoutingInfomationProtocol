package com.yue;

import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Router
 *
 * @author: Wenduo Yue
 * @date: 6/16/20
 */
public class DebugHelper {
    public enum Level {
        NONE, INFO, DEBUG
    }

    public static Level logLevel = Level.INFO;

    public static void Log(Level level, String s) {
        if (logLevel.compareTo(level) >= 0)
            System.out.println(new SimpleDateFormat("HH:mm:ss>> ").format(new Date()) + s);
    }
}
