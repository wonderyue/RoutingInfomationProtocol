package com.yue;
/**
 * Router
 *
 * @author: Wenduo Yue
 * @date: 6/16/20
 */
public class DebugHelper {
    public enum Level {
        NONE,
        INFO,
        DEBUG
    }
    public static Level logLevel = Level.INFO;
    public static void Log(Level level, String s) {
        if (logLevel.compareTo(level) >= 0)
            System.out.println(s);
    }
}
