package com.yue;

/**
 * ProtoHandler
 *
 * @author: Wenduo Yue
 * @date: 6/23/20
 */
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

public class RipHandler extends SimpleChannelInboundHandler<Rip.Packet> {

    private Router router;

    RipHandler(Router router) {
        this.router = router;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Rip.Packet msg) {
        int from = msg.getRouterId();
        DebugHelper.Log(DebugHelper.Level.INFO, msg.getCommand() + " from " + from);
        if (msg.getCommand() == Rip.Packet.Command.REQUEST) { // response with routing table
            router.sendResponse(from);
        } else if (msg.getCommand() == Rip.Packet.Command.RESPONSE) { // try update routing table
            router.updateRouteTable(from, msg.getRouterEntriesList());
        } else if (msg.getCommand() == Rip.Packet.Command.DISCONNECT) { // simulating disconnection
            router.disconnect(from);
        }
    }
}