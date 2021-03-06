/*
 * Copyright (c) 2014-2015 VMware, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.  You may obtain a copy of
 * the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, without warranties or
 * conditions of any kind, EITHER EXPRESS OR IMPLIED.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package com.vmware.xenon.common.http.netty;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.SortedMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.SSLContext;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.cookie.ClientCookieDecoder;
import io.netty.handler.codec.http.cookie.Cookie;

import com.vmware.xenon.common.Operation;
import com.vmware.xenon.common.Operation.AuthorizationContext;
import com.vmware.xenon.common.Operation.CompletionHandler;
import com.vmware.xenon.common.OperationContext;
import com.vmware.xenon.common.Service.Action;
import com.vmware.xenon.common.ServiceClient;
import com.vmware.xenon.common.ServiceErrorResponse;
import com.vmware.xenon.common.ServiceErrorResponse.ErrorDetail;
import com.vmware.xenon.common.ServiceHost;
import com.vmware.xenon.common.UriUtils;
import com.vmware.xenon.common.Utils;
import com.vmware.xenon.common.http.netty.NettyChannelPool.NettyChannelGroupKey;
import com.vmware.xenon.services.common.ServiceUriPaths;

/**
 * Asynchronous request / response client with concurrent connection management
 */
public class NettyHttpServiceClient implements ServiceClient {
    /**
     * Number of maximum parallel connections to a remote host. Idle connections are groomed but if
     * this limit is set too high, and we are talking to many remote hosts, we can possibly exceed
     * the process file descriptor limit
     */
    public static final int DEFAULT_CONNECTIONS_PER_HOST = ServiceClient.DEFAULT_CONNECTION_LIMIT_PER_HOST;

    public static final Logger LOGGER = Logger.getLogger(ServiceClient.class
            .getName());
    private static final String ENV_VAR_NAME_HTTP_PROXY = "http_proxy";

    private URI httpProxy;
    private String userAgent;

    private NettyChannelPool sslChannelPool;
    private NettyChannelPool channelPool;
    private NettyChannelPool http2ChannelPool;
    private SortedMap<Long, Operation> pendingRequests = new ConcurrentSkipListMap<>();

    private ScheduledExecutorService scheduledExecutor;

    private ExecutorService executor;

    private SSLContext sslContext;

    private ServiceHost host;

    private HttpRequestCallbackService callbackService;

    CookieJar cookieJar = new CookieJar();

    private boolean isStarted;

    public static ServiceClient create(String userAgent,
            ExecutorService executor,
            ScheduledExecutorService scheduledExecutor) throws URISyntaxException {
        return create(userAgent, executor, scheduledExecutor, null);
    }

    public static ServiceClient create(String userAgent,
            ExecutorService executor,
            ScheduledExecutorService scheduledExecutor,
            ServiceHost host) throws URISyntaxException {
        NettyHttpServiceClient sc = new NettyHttpServiceClient();
        sc.userAgent = userAgent;
        sc.scheduledExecutor = scheduledExecutor;
        sc.executor = executor;
        sc.host = host;
        sc.channelPool = new NettyChannelPool();
        sc.http2ChannelPool = new NettyChannelPool();
        String proxy = System.getenv(ENV_VAR_NAME_HTTP_PROXY);
        if (proxy != null) {
            sc.setHttpProxy(new URI(proxy));
        }

        sc.setConnectionLimitPerTag(ServiceClient.CONNECTION_TAG_DEFAULT,
                DEFAULT_CONNECTIONS_PER_HOST);
        sc.setConnectionLimitPerTag(ServiceClient.CONNECTION_TAG_HTTP2_DEFAULT,
                DEFAULT_CONNECTION_LIMIT_PER_TAG);
        return sc.setConnectionLimitPerHost(DEFAULT_CONNECTIONS_PER_HOST);
    }

    private String buildThreadTag() {
        if (this.host != null) {
            return UriUtils.extendUri(this.host.getUri(), "netty-client").toString();
        }
        return getClass().getSimpleName() + ":" + Utils.getNowMicrosUtc();
    }

    @Override
    public void start() {
        synchronized (this) {
            if (this.isStarted) {
                return;
            }
            this.isStarted = true;
        }

        this.channelPool.setThreadTag(buildThreadTag());
        this.channelPool.setThreadCount(Math.min(Utils.DEFAULT_IO_THREAD_COUNT, 2));
        this.channelPool.start();

        // We make a separate pool for HTTP/2. We want to have only one connection per host
        // when using HTTP/2 since HTTP/2 multiplexes streams on a single connection.
        this.http2ChannelPool.setThreadTag(buildThreadTag());
        this.http2ChannelPool.setThreadCount(Utils.DEFAULT_IO_THREAD_COUNT);
        if (this.host != null) {
            this.http2ChannelPool.setExecutor(this.executor);
        }
        this.http2ChannelPool.setHttp2Only();
        this.http2ChannelPool.start();

        if (this.sslContext != null) {
            this.sslChannelPool = new NettyChannelPool();
            this.sslChannelPool.setConnectionLimitPerHost(getConnectionLimitPerHost());
            this.sslChannelPool.setThreadTag(buildThreadTag());
            this.sslChannelPool.setThreadCount(Utils.DEFAULT_IO_THREAD_COUNT);
            this.sslChannelPool.setSSLContext(this.sslContext);
            this.sslChannelPool.start();
        }

        if (this.host != null) {
            Operation startCallbackPost = Operation
                    .createPost(UriUtils.buildUri(this.host, ServiceUriPaths.CORE_CALLBACKS))
                    .setCompletion((o, e) -> {
                        if (e != null) {
                            this.host.log(Level.WARNING, "Failed to start %s: %s",
                                    ServiceUriPaths.CORE_CALLBACKS,
                                    e.toString());
                        }
                    });
            this.callbackService = new HttpRequestCallbackService();
            this.host.startService(startCallbackPost, this.callbackService);
        }
    }

    @Override
    public void stop() {
        this.channelPool.stop();
        if (this.sslChannelPool != null) {
            this.sslChannelPool.stop();
        }
        if (this.http2ChannelPool != null) {
            this.http2ChannelPool.stop();
        }
        // In practice, it's safe not to synchornize here, but this make Findbugs happy.
        synchronized (this) {
            this.isStarted = false;
        }

        if (this.host != null) {
            this.host.stopService(this.callbackService);
        }

        this.pendingRequests.clear();
    }

    public ServiceClient setHttpProxy(URI proxy) {
        this.httpProxy = proxy;
        return this;
    }

    @Override
    public void send(Operation op) {
        this.sendRequest(op);
    }

    private void sendSingleRequest(Operation op) {
        Operation clone = clone(op);
        if (clone == null) {
            return;
        }

        setExpiration(clone);

        setCookies(clone);

        // if operation has a context id, set it on the local thread, otherwise, set the
        // context id from thread, on the operation
        if (op.getContextId() != null) {
            OperationContext.setContextId(op.getContextId());
        } else {
            clone.setContextId(OperationContext.getContextId());
        }

        OperationContext ctx = OperationContext.getOperationContext();

        try {
            // First attempt in process delivery to co-located host
            if (!op.isRemote()) {
                if (this.host != null && this.host.handleRequest(clone)) {
                    return;
                }
            }
            sendRemote(clone);
        } finally {
            // we must restore the operation context after each send, since
            // it can be reset by the host, depending on queuing and dispatching behavior
            OperationContext.restoreOperationContext(ctx);
        }
    }

    private void setExpiration(Operation op) {
        if (op.getExpirationMicrosUtc() == 0) {
            long defaultTimeoutMicros = ServiceHost.ServiceHostState.DEFAULT_OPERATION_TIMEOUT_MICROS;
            if (this.host != null) {
                defaultTimeoutMicros = this.host.getOperationTimeoutMicros();
            }
            op.setExpiration(Utils.getNowMicrosUtc() + defaultTimeoutMicros);
        }
    }

    private void addToPending(Operation op) {
        this.pendingRequests.put(op.getExpirationMicrosUtc(), op);
    }

    private void removeFromPending(Operation op) {
        this.pendingRequests.remove(op.getExpirationMicrosUtc());
    }

    private void sendRemote(Operation op) {
        addToPending(op);
        connect(op);
    }

    /**
     * Sends a request using the asynchronous HTTP pattern, allowing greater connection re-use. The
     * send method creates a lightweight service that serves as the callback URI for receiving the
     * completion status from the remote node. The callback URI is set as a header on the out bound
     * request.
     *
     * The remote node, if it detects the presence of the callback location header, will create a
     * new, local request, send it to the local service, and when that local request completes, it
     * will issues a PATCH to the callback service on this node. The original request will then be
     * completed and the client will see the response.
     *
     * The end result is that a TCP connection is not "blocked" while we wait for the remote node to
     * return a response (similar to the benefits of the asynchronous REST pattern for services that
     * implement it)
     */
    @Override
    public void sendWithCallback(Operation req) {
        Operation op = clone(req);
        if (op == null) {
            return;
        }

        setExpiration(op);

        if (!req.isRemote() && this.host != null && this.host.handleRequest(op)) {
            // request was accepted by an in-process service host
            return;
        }

        // Queue operation, then send it to remote target. At some point later the remote host will
        // send a PATCH
        // to the callback service to complete this pending operation
        URI u = this.callbackService.queueUntilCallback(op);
        Operation remoteOp = op.clone();
        remoteOp.setRequestCallbackLocation(u);
        remoteOp.setCompletion((o, e) -> {
            if (e != null) {
                // we do not remove the operation from the callback service, it will be removed on
                // next maintenance
                op.setExpiration(0).fail(e);
                return;
            }
            // release reference to body, not needed
            op.setBody(null);
        });
        sendRemote(remoteOp);
    }

    private void setCookies(Operation clone) {
        if (this.cookieJar.isEmpty()) {
            return;
        }

        // Set cookies for outbound request
        clone.setCookies(this.cookieJar.list(clone.getUri()));
    }

    private void updateCookieJarFromResponseHeaders(Operation op) {
        String value = op.getResponseHeader(Operation.SET_COOKIE_HEADER);
        if (value == null) {
            return;
        }

        Cookie cookie = ClientCookieDecoder.LAX.decode(value);
        if (cookie == null) {
            return;
        }

        this.cookieJar.add(op.getUri(), cookie);
    }

    private void connect(Operation op) {
        final Object originalBody = op.getBodyRaw();

        // We know the URI is not null, because it was checked in clone()
        if (op.getUri().getHost() == null) {
            op.setRetryCount(0);
            fail(new IllegalArgumentException("Missing host in URI"),
                    op, originalBody);
            return;
        }

        String host = op.getUri().getHost();
        String scheme = op.getUri().getScheme();
        int port = op.getUri().getPort();

        if (this.httpProxy != null && !ServiceHost.LOCAL_HOST.equals(host)) {
            host = this.httpProxy.getHost();
            port = this.httpProxy.getPort();
            scheme = this.httpProxy.getScheme();
        }

        op.nestCompletion((o, e) -> {
            if (o.getStatusCode() == Operation.STATUS_CODE_TIMEOUT) {
                failWithTimeout(op, originalBody);
                return;
            }
            if (e != null) {
                op.setBody(ServiceErrorResponse.create(e, Operation.STATUS_CODE_BAD_REQUEST,
                        EnumSet.of(ErrorDetail.SHOULD_RETRY)));
                fail(e, op, originalBody);
                return;
            }
            doSendRequest(op);
        });

        NettyChannelPool pool = this.channelPool;

        if (op.isConnectionSharing()) {
            pool = this.http2ChannelPool;
        }

        if (scheme.equals(UriUtils.HTTP_SCHEME)) {
            if (port == -1) {
                port = UriUtils.HTTP_DEFAULT_PORT;
            }
        } else if (scheme.equals(UriUtils.HTTPS_SCHEME)) {
            if (port == -1) {
                port = UriUtils.HTTPS_DEFAULT_PORT;
            }
            pool = this.sslChannelPool;

            if (this.getSSLContext() == null || pool == null) {
                op.setRetryCount(0);
                fail(new IllegalArgumentException(
                        "HTTPS not enabled, set SSL context before starting client:"
                                + op.getUri().getScheme()),
                        op, originalBody);
                return;
            }
        } else {
            op.setRetryCount(0);
            fail(new IllegalArgumentException(
                    "Scheme is not supported: " + op.getUri().getScheme()), op, originalBody);
            return;
        }

        NettyChannelGroupKey key = new NettyChannelGroupKey(
                op.getConnectionTag(), host, port, pool.isHttp2Only());
        pool.connectOrReuse(key, op);
    }

    @Override
    public void sendRequest(Operation op) {
        sendSingleRequest(op);
    }

    private void doSendRequest(Operation op) {
        final Object originalBody = op.getBodyRaw();
        try {
            byte[] body = Utils.encodeBody(op);
            String pathAndQuery;
            String path = op.getUri().getPath();
            String query = op.getUri().getRawQuery();
            path = path == null || path.isEmpty() ? "/" : path;
            if (query != null) {
                pathAndQuery = path + "?" + query;
            } else {
                pathAndQuery = path;
            }

            /**
             * NOTE: Pay close attention to calls that access the operation request headers, since
             * they will cause a memory allocation. We avoid the allocation by first checking if
             * the operation has any custom headers to begin with, then we check for the specific
             * header
             */
            boolean hasRequestHeaders = op.hasRequestHeaders();
            boolean useHttp2 = op.isConnectionSharing();
            if (this.httpProxy != null || useHttp2) {
                pathAndQuery = op.getUri().toString();
            }

            NettyFullHttpRequest request = null;
            HttpMethod method = HttpMethod.valueOf(op.getAction().toString());
            if (body == null || body.length == 0) {
                request = new NettyFullHttpRequest(HttpVersion.HTTP_1_1, method, pathAndQuery,
                        Unpooled.buffer(0), false);
            } else {
                ByteBuf content = Unpooled.wrappedBuffer(body, 0, (int) op.getContentLength());
                request = new NettyFullHttpRequest(HttpVersion.HTTP_1_1, method, pathAndQuery,
                        content, false);
            }

            if (useHttp2) {
                // when operation is cloned, it may contain original streamId header. remove it.
                if (hasRequestHeaders) {
                    op.getRequestHeaders().remove(Operation.STREAM_ID_HEADER);
                }

                // We set the operation so that once a streamId is assigned, we can record
                // the correspondence between the streamId and operation: this will let us
                // handle responses properly later.
                request.setOperation(op);
            }

            String pragmaHeader = op.getRequestHeader(Operation.PRAGMA_HEADER);

            if (op.isFromReplication() && pragmaHeader == null) {
                request.headers().set(HttpHeaderNames.PRAGMA,
                        Operation.PRAGMA_DIRECTIVE_REPLICATED);
            }

            if (op.getTransactionId() != null) {
                request.headers().set(Operation.TRANSACTION_ID_HEADER, op.getTransactionId());
            }

            if (op.getContextId() != null) {
                request.headers().set(Operation.CONTEXT_ID_HEADER, op.getContextId());
            }

            AuthorizationContext ctx = op.getAuthorizationContext();
            if (ctx != null && ctx.getToken() != null) {
                request.headers().set(Operation.REQUEST_AUTH_TOKEN_HEADER, ctx.getToken());
            }

            if (hasRequestHeaders) {
                for (Entry<String, String> nameValue : op.getRequestHeaders().entrySet()) {
                    request.headers().set(nameValue.getKey(), nameValue.getValue());
                }
            }

            request.headers().set(HttpHeaderNames.CONTENT_LENGTH,
                    Long.toString(op.getContentLength()));
            request.headers().set(HttpHeaderNames.CONTENT_TYPE, op.getContentType());
            request.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);

            if (!op.isFromReplication()) {
                if (op.getCookies() != null) {
                    String header = CookieJar.encodeCookies(op.getCookies());
                    request.headers().set(HttpHeaderNames.COOKIE, header);
                }

                if (op.hasReferer()) {
                    request.headers().set(HttpHeaderNames.REFERER, op.getRefererAsString());
                }

                request.headers().set(HttpHeaderNames.USER_AGENT, this.userAgent);
                if (op.getRequestHeader(Operation.ACCEPT_HEADER) == null) {
                    request.headers().set(HttpHeaderNames.ACCEPT,
                            Operation.MEDIA_TYPE_EVERYTHING_WILDCARDS);
                }

                request.headers().set(HttpHeaderNames.HOST, op.getUri().getHost());
            }

            op.nestCompletion((o, e) -> {
                if (e != null) {
                    fail(e, op, originalBody);
                    return;
                }

                removeFromPending(op);

                updateCookieJarFromResponseHeaders(o);

                // After request is sent control is transferred to the
                // NettyHttpServerResponseHandler. The response handler will nest completions
                // and call complete() when response is received, which will invoke this completion
                op.complete();
            });

            op.getSocketContext().writeHttpRequest(request);
        } catch (Throwable e) {
            op.setBody(ServiceErrorResponse.create(e, Operation.STATUS_CODE_BAD_REQUEST,
                    EnumSet.of(ErrorDetail.SHOULD_RETRY)));
            fail(e, op, originalBody);
        }
    }

    private void failWithTimeout(Operation op, Object originalBody) {
        Throwable e = new TimeoutException(op.getUri() + ":" + op.getExpirationMicrosUtc());
        op.setBodyNoCloning(
                ServiceErrorResponse.create(e, Operation.STATUS_CODE_TIMEOUT,
                        EnumSet.of(ErrorDetail.SHOULD_RETRY)));
        fail(e, op, originalBody);
    }

    private void fail(Throwable e, Operation op, Object originalBody) {
        removeFromPending(op);
        NettyChannelContext ctx = (NettyChannelContext) op.getSocketContext();
        NettyChannelPool pool = this.channelPool;

        if (this.sslChannelPool != null && this.sslChannelPool.isContextInUse(ctx)) {
            pool = this.sslChannelPool;
        }

        if (ctx != null && ctx.getProtocol() == NettyChannelContext.Protocol.HTTP2) {
            // For HTTP/2, we multiple streams so we don't close the connection.
            pool = this.http2ChannelPool;
            pool.returnOrClose(ctx, false);
        } else {
            // for HTTP/1.1, we close the stream to ensure we don't use a bad connection
            op.setSocketContext(null);
            pool.returnOrClose(ctx, !op.isKeepAlive());
        }

        if (this.scheduledExecutor.isShutdown()) {
            op.fail(new CancellationException());
            return;
        }

        boolean isRetryRequested = op.getRetryCount() > 0 && op.decrementRetriesRemaining() >= 0;

        if (isRetryRequested) {
            if (op.getStatusCode() >= Operation.STATUS_CODE_SERVER_FAILURE_THRESHOLD) {
                isRetryRequested = false;
            } else if (op.getStatusCode() == Operation.STATUS_CODE_CONFLICT) {
                isRetryRequested = false;
            } else if (op.getStatusCode() == Operation.STATUS_CODE_UNAUTHORIZED) {
                isRetryRequested = false;
            } else if (op.getStatusCode() == Operation.STATUS_CODE_NOT_FOUND) {
                isRetryRequested = false;
            } else if (op.getStatusCode() == Operation.STATUS_CODE_FORBIDDEN) {
                isRetryRequested = false;
            }
        }

        if (!isRetryRequested) {
            LOGGER.fine(String.format("(%d) Send of %d, from %s to %s failed with %s",
                    pool.getPendingRequestCount(op), op.getId(), op.getRefererAsString(),
                    op.getUri(),
                    e.toString()));
            op.fail(e);
            return;
        }

        LOGGER.fine(String.format("(%d) Retry %d of request %d from %s to %s due to %s",
                pool.getPendingRequestCount(op), op.getRetryCount() - op.getRetriesRemaining(),
                op.getId(),
                op.getRefererAsString(), op.getUri(), e.toString()));

        int delaySeconds = op.getRetryCount() - op.getRetriesRemaining();

        // restore status code and body, then restart send state machine
        // (connect, encode, write to channel)
        op.setStatusCode(Operation.STATUS_CODE_OK).setBodyNoCloning(originalBody);

        this.scheduledExecutor.schedule(() -> {
            connect(op);
        } , delaySeconds, TimeUnit.SECONDS);
    }

    private static Operation clone(Operation op) {

        Throwable e = null;
        if (op == null) {
            throw new IllegalArgumentException("Operation is required");
        }

        CompletionHandler c = op.getCompletion();

        if (op.getUri() == null) {
            e = new IllegalArgumentException("Uri is required");
        }

        if (op.getAction() == null) {
            e = new IllegalArgumentException("Action is required");
        }

        if (!op.hasReferer()) {
            e = new IllegalArgumentException("Referer is required");
        }

        boolean needsBody = op.getAction() != Action.GET && op.getAction() != Action.DELETE &&
                op.getAction() != Action.POST && op.getAction() != Action.OPTIONS;

        if (!op.hasBody() && needsBody) {
            e = new IllegalArgumentException("Body is required");
        }

        if (e != null) {
            if (c != null) {
                c.handle(op, e);
                return null;
            } else {
                throw new RuntimeException(e);
            }
        }
        return op.clone();
    }

    @Override
    public void handleMaintenance(Operation op) {
        if (this.sslChannelPool != null) {
            this.sslChannelPool.handleMaintenance(Operation.createPost(op.getUri()));
        }
        if (this.http2ChannelPool != null) {
            this.http2ChannelPool.handleMaintenance(Operation.createPost(op.getUri()));
        }
        this.channelPool.handleMaintenance(op);

        failExpiredRequests(Utils.getNowMicrosUtc());
    }

    /**
     * Periodically check our pending request sorted map for expired operations. Since
     * maintenance (this method) runs in parallel with the connect and send state machine,
     * we need to be careful on how we fail expired operations. The connect() method
     * uses nestCompletion() which is meant to be used in a asynchronous, but isolated
     * flow over a single operation, where only one stage acts on the operation at a time.
     * We violate this design requirement, since we want to avoid locks, and we want to
     * leverage the maintenance interval. So, we run two passes, first marking the operation
     * with a status code, allowing the parallel connect/send pipeline to avoid triggering
     * a complete() and non atomic roll back of the nested completions.
     */
    private void failExpiredRequests(long now) {
        if (this.pendingRequests.isEmpty()) {
            return;
        }

        Iterator<Entry<Long, Operation>> itPending = this.pendingRequests.entrySet().iterator();
        while (itPending.hasNext()) {
            Entry<Long, Operation> entry = itPending.next();
            long exp = entry.getKey();
            if (exp > now) {
                break;
            }
            Operation o = entry.getValue();
            if (o.getStatusCode() == Operation.STATUS_CODE_TIMEOUT) {
                // second pass, fail operation already marked as expired
                failWithTimeout(o, o.getBodyRaw());
            } else {
                // first pass, just mark as expired, but do not fail.
                o.setStatusCode(Operation.STATUS_CODE_TIMEOUT);
            }
        }
    }


    /**
     * @see ServiceClient#setConnectionLimitPerHost(int)
     */
    @Override
    public ServiceClient setConnectionLimitPerHost(int limit) {
        this.channelPool.setConnectionLimitPerHost(limit);
        if (this.sslChannelPool != null) {
            this.sslChannelPool.setConnectionLimitPerHost(limit);
        }
        if (this.http2ChannelPool != null) {
            this.http2ChannelPool.setConnectionLimitPerHost(limit);
        }
        return this;
    }

    /**
     * @see ServiceClient#getConnectionLimitPerHost()
     */
    @Override
    public int getConnectionLimitPerHost() {
        return this.channelPool.getConnectionLimitPerHost();
    }

    /**
     * @see ServiceClient#setConnectionLimitPerTag(String, int)
     */
    @Override
    public ServiceClient setConnectionLimitPerTag(String tag, int limit) {
        this.channelPool.setConnectionLimitPerTag(tag, limit);
        if (this.sslChannelPool != null) {
            this.sslChannelPool.setConnectionLimitPerTag(tag, limit);
        }
        if (this.http2ChannelPool != null) {
            this.http2ChannelPool.setConnectionLimitPerTag(tag, limit);
        }
        return this;
    }

    /**
     * @see ServiceClient#getConnectionLimitPerTag(String)
     */
    @Override
    public int getConnectionLimitPerTag(String tag) {
        return this.channelPool.getConnectionLimitPerTag(tag);
    }

    @Override
    public ServiceClient setSSLContext(SSLContext context) {
        this.sslContext = context;
        return this;
    }

    @Override
    public SSLContext getSSLContext() {
        return this.sslContext;
    }

    public NettyChannelPool getChannelPool() {
        return this.channelPool;
    }

    public NettyChannelPool getHttp2ChannelPool() {
        return this.http2ChannelPool;
    }

    public NettyChannelPool getSslChannelPool() {
        return this.sslChannelPool;
    }

    /**
     * Find the HTTP/2 context that is currently being used to talk to a given host.
     * This is intended for infrastructure test purposes.
     */
    public NettyChannelContext getInUseHttp2Context(String tag, String host, int port) {
        if (this.http2ChannelPool == null) {
            throw new IllegalStateException("Internal error: no HTTP/2 channel pool");
        }
        return this.http2ChannelPool.getFirstValidHttp2Context(tag, host, port);
    }

    /**
     * Count how many HTTP/2 contexts we have. There may be more than one if we have
     * an exhausted connection that hasn't been cleaned up yet.
     * This is intended for infrastructure test purposes.
     */
    public int getInUseContextCount(String tag, String host, int port) {
        if (this.http2ChannelPool == null) {
            throw new IllegalStateException("Internal error: no HTTP/2 channel pool");
        }
        return this.http2ChannelPool.getHttp2ActiveContextCount(tag, host, port);
    }

    /**
     * Infrastructure testing use only: do not use this in production
     */
    public void clearCookieJar() {
        this.cookieJar = new CookieJar();
    }
}
