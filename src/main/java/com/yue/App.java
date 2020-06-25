package com.yue;

import java.io.FileNotFoundException;

import org.apache.log4j.PropertyConfigurator;

/**
 * Hello world!
 *
 */
public class App {

    public static void main(String[] args) throws FileNotFoundException, InterruptedException {
        // initialize log4j
        PropertyConfigurator.configure("log4j.properties");
        // parse router id from arguments
        int id = args.length == 0 ? 1 : Integer.parseInt(args[0]);
        Router.MODE mode = args.length < 2 ? Router.MODE.NORMAL : Router.MODE.values()[Integer.parseInt(args[1])];
        Router router = new Router(id, mode);
        router.init();
        router.run();
    }
}
