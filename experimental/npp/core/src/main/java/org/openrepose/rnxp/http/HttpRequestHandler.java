package org.openrepose.rnxp.http;

import org.jboss.netty.channel.Channel;
import org.openrepose.rnxp.http.context.RequestContext;
import org.openrepose.rnxp.http.io.control.BlockingConnectionController;
import org.jboss.netty.channel.ChannelStateEvent;
import org.openrepose.rnxp.PowerProxy;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.openrepose.rnxp.decoder.partial.ContentMessagePartial;
import org.openrepose.rnxp.decoder.partial.HttpMessagePartial;
import org.openrepose.rnxp.http.context.SimpleRequestContext;
import org.openrepose.rnxp.http.io.control.HttpConnectionController;
import org.openrepose.rnxp.http.proxy.ClientPipelineFactory;
import org.openrepose.rnxp.http.proxy.NettyOriginConnectionFuture;
import org.openrepose.rnxp.http.proxy.InboundOutboundCoordinator;
import org.openrepose.rnxp.http.proxy.OriginChannelFactory;
import org.openrepose.rnxp.http.proxy.OriginConnectionFuture;
import org.openrepose.rnxp.http.proxy.StreamController;
import org.openrepose.rnxp.netty.valve.ChannelReadValve;
import org.openrepose.rnxp.netty.valve.ChannelValve;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author zinic
 */
public class HttpRequestHandler extends SimpleChannelUpstreamHandler {

    private static final Logger LOG = LoggerFactory.getLogger(HttpRequestHandler.class);
    
    private final OriginChannelFactory proxyChannelFactory;
    private final StreamController streamController;
    private final RequestContext requestContext;
    
    private InboundOutboundCoordinator coordinator;
    private ChannelValve inboundReadValve;
    private HttpConnectionController updateController;

    public HttpRequestHandler(PowerProxy powerProxyInstance, OriginChannelFactory proxyChannelFactory, StreamController streamController) {
        requestContext = new SimpleRequestContext(powerProxyInstance);
        
        this.proxyChannelFactory = proxyChannelFactory;
        this.streamController = streamController;
    }

    @Override
    public void channelOpen(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
        final Channel channel = e.getChannel();

        // Set up the read valve and shut the channel for now
        inboundReadValve = new ChannelReadValve(channel, ChannelReadValve.VALVE_OPEN);
        inboundReadValve.shut();

        // Channel coordinator manages reading from a source and writing to a destination
        coordinator = new InboundOutboundCoordinator();
        
        // Default coordination happens with the request channel in the case where we don't route to the origin
        coordinator.setInboundChannel(channel, streamController);
        coordinator.setOutboundChannel(channel, streamController);
        
        final OriginConnectionFuture originConnectionFuture = new NettyOriginConnectionFuture(
                new ClientPipelineFactory(requestContext, coordinator), proxyChannelFactory);

        // Set up our update controller for Request < -- > Channel communication
        updateController = new BlockingConnectionController(coordinator, inboundReadValve);

        // Let's kick off the worker thread
        requestContext.startRequest(updateController, originConnectionFuture);
    }

    @Override
    public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
        final HttpMessagePartial partial = (HttpMessagePartial) e.getMessage();

        if (HttpMessageComponent.UNPARSED_STREAMABLE == partial.getHttpMessageComponent()) {
            coordinator.streamOutbound(((ContentMessagePartial)partial).getData());
        }

        if (!partial.isError()) {
            // Apply the partial
            updateController.applyPartial(partial);
        } else {
            // TODO:Implement - Write error output functionality here
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) throws Exception {
        final Throwable cause = e.getCause();
        
        LOG.error(cause.getMessage(), cause);
        e.getChannel().close();
    }
}
