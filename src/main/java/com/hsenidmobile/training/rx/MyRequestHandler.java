package com.hsenidmobile.training.rx;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsenidmobile.training.rx.api.PlatformProficiency;
import com.hsenidmobile.training.rx.api.Profile;
import com.hsenidmobile.training.rx.api.WorkHistory;
import com.hsenidmobile.training.rx.async.PlatformProficienciesCallable;
import com.hsenidmobile.training.rx.async.WorkHistoryCallable;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.*;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.reactivex.Observable;
import io.reactivex.schedulers.Schedulers;

import static io.netty.handler.codec.http.HttpResponseStatus.*;
import static io.netty.handler.codec.http.HttpVersion.*;

import java.util.concurrent.*;

/**
 * Created by isuru on 11/12/2017.
 */
public class MyRequestHandler extends ChannelInboundHandlerAdapter {

    private final ExecutorService workHistoryThreadPool = Executors.newFixedThreadPool(1, new DefaultThreadFactory("work-history"));
    private final ExecutorService platformProficiencyThreadPool = Executors.newFixedThreadPool(1, new DefaultThreadFactory("platform-proficiency"));
    private final ExecutorService transportThreadPool = Executors.newFixedThreadPool(1, new DefaultThreadFactory("transport"));

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        ctx.flush();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof HttpRequest) {
            FullHttpRequest req = (FullHttpRequest) msg;
            FullHttpResponse response = null;

            Observable.fromCallable(new WorkHistoryCallable())
                    .subscribeOn(Schedulers.from(workHistoryThreadPool))
                    .zipWith(Observable.fromCallable(new PlatformProficienciesCallable()).subscribeOn(Schedulers.from(platformProficiencyThreadPool)), (workHistory, platformProficiency) -> {
                Profile profile = new Profile();
                profile.setPlatformProficiency(platformProficiency);
                profile.setWorkHistory(workHistory);
                return profile;
            }).observeOn(Schedulers.from(transportThreadPool))
                    .subscribe(profile -> itsJustTransport(ctx, req, profile));
        }
    }

    /**
     * This is a garbage code, but that's not the point.
     * */
    public <T> T unsafeFutureGet(Future<T> future) {
        try {
            return future.get();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    private void itsJustTransport(ChannelHandlerContext ctx, FullHttpRequest req, Profile profile) {
        System.out.println("Transport (Observer) - Got called by " + Thread.currentThread().getName());
        FullHttpResponse response;ObjectMapper objectMapper = new ObjectMapper();
        byte[] responseBytes = new byte[]{};
        try {
            responseBytes = objectMapper.writeValueAsBytes(profile);
        } catch (JsonProcessingException e) {
            e.printStackTrace();
        }

        response = new DefaultFullHttpResponse(HTTP_1_1, OK, Unpooled.wrappedBuffer(responseBytes));

        boolean keepAlive = HttpUtil.isKeepAlive(req);
        response.headers().set("Content-Type", "application/json");
        response.headers().setInt("Content-Length", response.content().readableBytes());

        if (!keepAlive) {
            ctx.write(response).addListener(ChannelFutureListener.CLOSE);
        } else {
            response.headers().set("Connection", "keep-alive");
            ctx.write(response);
        }
        ctx.flush();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        cause.printStackTrace();
        ctx.close();
    }
}
