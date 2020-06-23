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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

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

    private class RouterEntry {
        int dest;
        int nextHop;
        int metric;

        RouterEntry(int d, int n, int m) {
            dest = d;
            nextHop = n;
            metric = m;
        }
    }

    private int id;
    Config config;
    Bootstrap bootstrap;
    NioEventLoopGroup group;
    Channel udpChannel;
    byte[] requestBytes;
    byte[] routingTableBytes;
    ConcurrentHashMap<Integer, InetSocketAddress> addressMap; // key: routerId
    Map<Integer, RouterEntry> routingTable; // key: routerId
    boolean isDirty; // True if routing table has been changed

    Router(int id) {
        this.id = id;
    }

    public void init() throws FileNotFoundException {
        // parse config file
        Gson gson = new Gson();
        config = gson.fromJson(new JsonReader(new FileReader("config.txt")), Config.class);
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
        routingTableBytes = new byte[0];
    }

    public void run() {
        try {
            // bind udp channel
            udpChannel = bootstrap.bind(config.protocol_port).sync().channel();
            // every config.regular_timer(30 by default) seconds, send Rip requests to neighbors
            ScheduledFuture future = group.scheduleAtFixedRate(() -> {
                for (int neighborId : config.routers.get(id).neighbors) {
                    sendRequest(neighborId);
                }
            }, 0, config.regular_timer, TimeUnit.SECONDS);
            // auto shutdown in config.shutdown_timer seconds
            if (config.shutdown_timer > 0) {
                group.schedule(() -> {
                    future.cancel(true);
                    udpChannel.close();
                    DebugHelper.Log(DebugHelper.Level.INFO, "Close channel.");
                }, config.shutdown_timer, TimeUnit.SECONDS);
            }
            // block for channel closing
            udpChannel.closeFuture().sync();
            group.shutdownGracefully();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private byte[] getRoutingTableBytes() {
        if (isDirty) { // if routing table has been updated, generate new bytes and set isDirty to false
            Rip.Packet.Builder builder = Rip.Packet.newBuilder().setCommand(Rip.Packet.Command.RESPONSE).setRouterId(id);
            for (RouterEntry routerEntry : routingTable.values()) {
                Rip.Packet.RouterEntry.Builder entryBuilder = Rip.Packet.RouterEntry.newBuilder()
                    .setDest(routerEntry.dest).setNextHop(routerEntry.nextHop).setMetric(routerEntry.metric);
                builder.addRouterEntries(entryBuilder);
            }
            routingTableBytes = builder.build().toByteArray();
            isDirty = false;
        }
        return routingTableBytes;
    }

    private void sendUdpMessage(int to, byte[] bytes) {
        InetSocketAddress address =
            addressMap.computeIfAbsent(to, k -> new InetSocketAddress(config.routers.get(to).ip, config.protocol_port));
        ByteBuf byteBuf = Unpooled.wrappedBuffer(bytes);// TODO
        DatagramPacket packet = new DatagramPacket(byteBuf, address);
        udpChannel.writeAndFlush(packet);
    }

    public void sendRequest(int to) {
        sendUdpMessage(to, requestBytes);
    }

    public void sendResponse(int to) {
        sendUdpMessage(to, getRoutingTableBytes());
    }

    public void updateRouteTable(int from, List<Rip.Packet.RouterEntry> list) {
        synchronized (routingTable) { // block
            for (Rip.Packet.RouterEntry entry : list) {
                if (!routingTable.containsKey(entry.getDest())) { // destination not exists
                    if (entry.getMetric() < Constants.INFINITE) { // if metric < 16, add entry to routing
                        RouterEntry routerEntry =
                            new RouterEntry(entry.getDest(), entry.getNextHop(), entry.getMetric());
                        routingTable.put(routerEntry.dest, routerEntry);
                        isDirty = true;
                    }
                } else if (entry.getNextHop() == from) { // if next hop is the sender of response message
                    RouterEntry routerEntry = routingTable.get(entry.getDest());
                    routerEntry.metric = entry.getMetric();
                    isDirty = true;
                } else {
                    RouterEntry routerEntry = routingTable.get(entry.getDest());
                    if (entry.getMetric() + 1 < routerEntry.metric) { // if new path is shorter, update routing table
                        routerEntry.metric = entry.getMetric() + 1;
                        routerEntry.nextHop = entry.getNextHop();
                        isDirty = true;
                    }
                }
            }
        }
        printRoutingTable();
    }

    private void printRoutingTable() {
        AsciiTable at = new AsciiTable();
        at.addRule();
        at.addRow("Destination", "Next Hop", "Metric");
        for (RouterEntry routerEntry : routingTable.values()) {
            at.addRule();
            at.addRow(routerEntry.dest, routerEntry.nextHop, routerEntry.metric);
        }
        at.addRule();
        DebugHelper.Log(DebugHelper.Level.INFO, at.render());
    }

    public static void main(String[] args) throws FileNotFoundException {
        // parse router id from arguments
        int id = args.length == 0 ? 0 : Integer.parseInt(args[0]);
        Router router = new Router(id);
        router.init();
        router.run();
    }
}
