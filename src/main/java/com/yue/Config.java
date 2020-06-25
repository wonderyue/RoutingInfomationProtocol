package com.yue;

/**
 * Router
 *
 * @author: Wenduo Yue
 * @date: 6/16/20
 */
import java.util.Map;

public class Config {
    public class RouterConfig {
        int id;
        String ip;
        int[] neighbors;
        Map<Integer, ACTION_TYPE> actions;
    }

    public enum ACTION_TYPE {
        DISCONNECT, JOIN,
    }

    int regular_timer = 30;
    int time_out_timer = 180;
    int gc_timer = 120;
    int shutdown_timer = 600;
    int protocol_port = 520;
    Map<Integer, RouterConfig> routers;
}
