package com.yue;

/**
 * Sender
 *
 * @author: Wenduo Yue
 * @date: 6/17/20
 */

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.gson.Gson;
import com.google.gson.stream.JsonReader;

import de.vandermeer.asciitable.AsciiTable;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.DatagramPacketDecoder;
import io.netty.handler.codec.DatagramPacketEncoder;
import io.netty.handler.codec.protobuf.ProtobufDecoder;
import io.netty.handler.codec.protobuf.ProtobufEncoder;
import io.netty.util.concurrent.ScheduledFuture;

public class Router {
    enum MODE {
        NORMAL, SPLIT_HORIZON, SPLIT_HORIZON_WITH_POISON_REVERSE,
    }

    private class RouterEntry {
        int dest;
        int nextHop;
        private int metric;

        public void setMetric(int m) {
            metric = Math.min(m, Constants.INFINITE);
        }

        public int getMetric() {
            return metric;
        }

        RouterEntry(int d, int n, int m) {
            dest = d;
            nextHop = n;
            metric = m;
        }
    }

    private int id;
    private MODE mode;
    Config config;
    Config.RouterConfig routerConfig;
    Bootstrap bootstrap;
    NioEventLoopGroup group;
    Channel udpChannel;
    byte[] requestBytes;
    ConcurrentHashMap<Integer, InetSocketAddress> addressMap; // key: routerId
    Map<Integer, RouterEntry> routingTable; // key: routerId
    ScheduledFuture future;

    Router(int id, MODE mode) {
        this.id = id;
        this.mode = mode;
    }

    public void init() throws FileNotFoundException {
        // parse config file
        Gson gson = new Gson();
        config = gson.fromJson(new JsonReader(new FileReader("config.txt")), Config.class);
        routerConfig = config.routers.get(id);
        // initialize udp channel
        group = new NioEventLoopGroup(10);
        bootstrap = new Bootstrap();
        RipHandler channelHandler = new RipHandler(this);
        bootstrap.group(group).channel(NioDatagramChannel.class).handler(new ChannelInitializer<NioDatagramChannel>() {
            @Override
            protected void initChannel(NioDatagramChannel nioDatagramChannel) {
                ChannelPipeline pipeline = nioDatagramChannel.pipeline();
                pipeline.addLast(new DatagramPacketDecoder(new ProtobufDecoder(Rip.Packet.getDefaultInstance())));
                pipeline.addLast(new DatagramPacketEncoder(new ProtobufEncoder()));
                pipeline.addLast(channelHandler);
            }
        });
        // reusable request bytes
        Rip.Packet pkg = Rip.Packet.newBuilder().setCommand(Rip.Packet.Command.REQUEST).setRouterId(id).build();
        requestBytes = pkg.toByteArray();
        // initialize address map and routing table
        addressMap = new ConcurrentHashMap<>();
        routingTable = new HashMap<>();
        for (int neighborId : config.routers.get(id).neighbors) {
            routingTable.put(neighborId, new RouterEntry(neighborId, neighborId, 1));
        }
        DebugHelper.Log(DebugHelper.Level.INFO, "start");
        printRoutingTable();
    }

    public void run() throws InterruptedException {
        DebugHelper.Log(DebugHelper.Level.INFO, "running");
        // bind udp channel
        udpChannel = bootstrap.bind(config.protocol_port).sync().channel();
        AtomicInteger round = new AtomicInteger();
        // every config.regular_timer(30 by default) seconds, send Rip requests to neighbors
        future = group.scheduleAtFixedRate(() -> {
            try {
                for (int neighborId : routerConfig.neighbors) {
                    sendRequest(neighborId);
                }
                if (routerConfig.actions != null && routerConfig.actions.containsKey(round.get())) {
                    executeAction(routerConfig.actions.get(round.get()));
                }
                round.getAndIncrement();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, 0, config.regular_timer, TimeUnit.SECONDS);
        // auto shutdown in config.shutdown_timer seconds
        if (config.shutdown_timer > 0) {
            group.schedule(() -> {
                closeChannel();
            }, config.shutdown_timer, TimeUnit.SECONDS);
        }
        // block for channel closing
        udpChannel.closeFuture().sync();
        group.shutdownGracefully();
    }

    private void closeChannel() {
        if (future != null && udpChannel != null) {
            future.cancel(true);
            udpChannel.close();
            DebugHelper.Log(DebugHelper.Level.INFO, "Close channel.");
        }
    }

    private void executeAction(Config.ACTION_TYPE type) {
        switch (type) {
            case DISCONNECT: // simulating disconnection
                Rip.Packet pkg =
                    Rip.Packet.newBuilder().setCommand(Rip.Packet.Command.DISCONNECT).setRouterId(id).build();
                byte[] bytes = pkg.toByteArray();
                for (int neighborId : routerConfig.neighbors) {
                    DebugHelper.Log(DebugHelper.Level.INFO, "Disconnect with " + neighborId);
                    sendUdpMessage(neighborId, bytes);
                }
                closeChannel();
                break;
            default:
                break;
        }
    }

    private byte[] getRoutingTableBytes(int to) {
        Rip.Packet.Builder builder = Rip.Packet.newBuilder().setCommand(Rip.Packet.Command.RESPONSE).setRouterId(id);
        for (RouterEntry routerEntry : routingTable.values()) {
            // do not advertise if next hop is the neighbor
            if (mode == MODE.SPLIT_HORIZON && routerEntry.nextHop == to) {
                continue;
            }
            Rip.Packet.RouterEntry.Builder entryBuilder = Rip.Packet.RouterEntry.newBuilder();
            entryBuilder.setDest(routerEntry.dest).setNextHop(routerEntry.nextHop);
            // advertise infinite if next hop is the neighbor
            if (mode == MODE.SPLIT_HORIZON_WITH_POISON_REVERSE && routerEntry.nextHop == to) {
                entryBuilder.setMetric(Constants.INFINITE);
            } else {
                entryBuilder.setMetric(routerEntry.getMetric());
            }
            builder.addRouterEntries(entryBuilder);
        }
        return builder.build().toByteArray();
    }

    private void sendUdpMessage(int to, byte[] bytes) {
        InetSocketAddress address =
            addressMap.computeIfAbsent(to, k -> new InetSocketAddress(config.routers.get(to).ip, config.protocol_port));
        ByteBuf byteBuf = Unpooled.wrappedBuffer(bytes);// TODO
        DatagramPacket packet = new DatagramPacket(byteBuf, address);
        udpChannel.writeAndFlush(packet);
    }

    public void sendRequest(int to) {
        DebugHelper.Log(DebugHelper.Level.INFO, "Send request to " + to);
        sendUdpMessage(to, requestBytes);
    }

    public void sendResponse(int to) {
        DebugHelper.Log(DebugHelper.Level.INFO, "Send response to " + to);
        sendUdpMessage(to, getRoutingTableBytes(to));
    }

    public void updateRouteTable(int from, List<Rip.Packet.RouterEntry> list) {
        synchronized (routingTable) { // block
            for (Rip.Packet.RouterEntry entry : list) {
                if (entry.getDest() == id) // dest cannot be the router itself
                    continue;
                if (!routingTable.containsKey(entry.getDest())) { // destination not exists
                    if (entry.getMetric() < Constants.INFINITE) { // if metric < 16, add entry to routing
                        RouterEntry routerEntry = new RouterEntry(entry.getDest(), from, entry.getMetric() + 1);
                        routingTable.put(routerEntry.dest, routerEntry);
                    }
                } else {
                    RouterEntry routerEntry = routingTable.get(entry.getDest());
                    int metric = entry.getMetric() + 1;
                    // if next hop is the sender of response message, update metric by force
                    if (routerEntry.nextHop == from) {
                        routerEntry.setMetric(metric);
                    } else {
                        // if new path is shorter, update routing table
                        if (metric < routerEntry.getMetric()) {
                            routerEntry.setMetric(metric);
                            routerEntry.nextHop = from;
                        }
                    }
                }
            }
        }
        printRoutingTable();
    }

    private void printRoutingTable() {
        AsciiTable at = new AsciiTable();
        at.addRule();
        at.addRow(null, null, "Router " + id);
        at.addRule();
        at.addRow("Destination", "Next Hop", "Metric");
        for (RouterEntry routerEntry : routingTable.values()) {
            at.addRule();
            at.addRow(routerEntry.dest, routerEntry.nextHop, routerEntry.metric);
        }
        at.addRule();
        DebugHelper.Log(DebugHelper.Level.INFO, "\n" + at.render());
    }

    public void disconnect(int routeId) {
        synchronized (routingTable) {
            routingTable.get(routeId).setMetric(Constants.INFINITE);
        }
        printRoutingTable();
    }
}
