package cc.lovezhy.raft.rpc;

import cc.lovezhy.raft.rpc.common.RpcExecutors;
import cc.lovezhy.raft.rpc.exception.RequestTimeoutException;
import cc.lovezhy.raft.rpc.protocal.RpcProtocalUtils;
import cc.lovezhy.raft.rpc.protocal.RpcRequest;
import cc.lovezhy.raft.rpc.protocal.RpcResponse;
import cc.lovezhy.raft.rpc.proxy.ConsumerRpcService;
import cc.lovezhy.raft.rpc.proxy.ProxyFactory;
import cc.lovezhy.raft.rpc.server.netty.NettyClient;
import cc.lovezhy.raft.rpc.server.netty.RpcService;
import cc.lovezhy.raft.rpc.util.LockObjectFactory.LockObject;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.SettableFuture;
import io.netty.channel.Channel;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static cc.lovezhy.raft.rpc.util.LockObjectFactory.getLockObject;

public class RpcClient<T> implements ConsumerRpcService, RpcService {

    public static <T> T create(Class<T> clazz, EndPoint endPoint) {
        Preconditions.checkNotNull(clazz);
        Preconditions.checkNotNull(endPoint);
        RpcClient<T> rpcClient = new RpcClient<>(clazz, endPoint);
        return rpcClient.getInstance();
    }

    private Class<T> clazz;

    private NettyClient nettyClient;

    private Map<String, LockObject> waitConditionMap = new ConcurrentHashMap<>();
    private Map<String, SettableFuture> rpcResponseFutureMap = new ConcurrentHashMap<>();
    private Map<String, RpcResponse> rpcResponseMap = new ConcurrentHashMap<>();

    private RpcClient(Class<T> clazz, EndPoint endPoint) {
        Preconditions.checkNotNull(clazz);
        Preconditions.checkNotNull(endPoint);
        RpcProtocalUtils.check(clazz);
        this.clazz = clazz;
        nettyClient = new NettyClient(endPoint, this);
        this.connect();
    }

    private void connect() {
        SettableFuture<Void> connectResultFuture = nettyClient.connect();
        Futures.addCallback(connectResultFuture, new FutureCallback<Void>() {
            @Override
            public void onSuccess(@Nullable Void result) {

            }

            @Override
            public void onFailure(Throwable t) {
                RpcExecutors.commonScheduledExecutor().schedule(() -> connect(), 200, TimeUnit.MILLISECONDS);
            }
        }, RpcExecutors.listeningScheduledExecutor());
    }

    private T getInstance() {
        return ProxyFactory.createRpcProxy(clazz, this);
    }

    @Override
    public RpcResponse sendRequest(RpcRequest request) throws RequestTimeoutException {
        String requestId = request.getRequestId();
        nettyClient.getChannel().writeAndFlush(request);
        LockObject lockObject = getLockObject();
        waitConditionMap.put(requestId, lockObject);

        synchronized (lockObject) {
            if (!rpcResponseMap.containsKey(requestId)) {
                try {
                    lockObject.wait(TimeUnit.MILLISECONDS.toMillis(50));
                } catch (InterruptedException e) {
                    // ignore
                }
            }
        }
        RpcResponse rpcResponse = rpcResponseMap.get(requestId);
        if (Objects.isNull(rpcResponse)) {
            throw new RequestTimeoutException("request time out");
        }
        waitConditionMap.remove(requestId);
        rpcResponseMap.remove(requestId);
        lockObject.recycle();
        return rpcResponse;
    }

    @Override
    public RpcResponse sendRequestAsync(RpcRequest request, long timeOutMills) {
        RpcResponse rpcResponse = new RpcResponse();
        SettableFuture future = SettableFuture.create();
        rpcResponse.setResponseBody(future);
        nettyClient.getChannel().writeAndFlush(request);
        rpcResponseFutureMap.put(request.getRequestId(), future);
        return rpcResponse;
    }

    @Override
    public void sendOneWayRequest(RpcRequest request) {
        // TODO
    }

    @Override
    public void onResponse(RpcResponse response) {
        String requestId = response.getRequestId();
        LockObject lockObject = waitConditionMap.get(requestId);
        if (Objects.isNull(lockObject)) {
            synchronized (lockObject) {
                rpcResponseMap.put(requestId, response);
                lockObject.notify();
            }
        } else {
            SettableFuture future = rpcResponseFutureMap.get(requestId);
            if (Objects.nonNull(future)) {
                future.set(response.getResponseBody());
            }
        }
    }

    @Override
    public void handleRequest(Channel channel, RpcRequest request) {
        throw new IllegalStateException();
    }
}
