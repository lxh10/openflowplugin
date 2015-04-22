/**
 * Copyright (c) 2015 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.openflowplugin.impl.connection.listener;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.JdkFutureAdapters;
import com.google.common.util.concurrent.ListenableFuture;
import java.net.InetSocketAddress;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.opendaylight.openflowplugin.api.openflow.connection.ConnectionContext;
import org.opendaylight.openflowplugin.api.openflow.device.Xid;
import org.opendaylight.yang.gen.v1.urn.opendaylight.openflow.protocol.rev130731.EchoInputBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.openflow.protocol.rev130731.EchoOutput;
import org.opendaylight.yang.gen.v1.urn.opendaylight.openflow.system.rev130927.DisconnectEvent;
import org.opendaylight.yang.gen.v1.urn.opendaylight.openflow.system.rev130927.SwitchIdleEvent;
import org.opendaylight.yang.gen.v1.urn.opendaylight.openflow.system.rev130927.SystemNotificationsListener;
import org.opendaylight.yangtools.yang.common.RpcError;
import org.opendaylight.yangtools.yang.common.RpcResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 */
public class SystemNotificationsListenerImpl implements SystemNotificationsListener {

    private ConnectionContext connectionContext;
    private static final Logger LOG = LoggerFactory.getLogger(SystemNotificationsListenerImpl.class);
    private static final long MAX_ECHO_REPLY_TIMEOUT = 2000;


    /**
     * @param connectionContext
     */
    public SystemNotificationsListenerImpl(ConnectionContext connectionContext) {
        this.connectionContext = connectionContext;
    }

    @Override
    public void onDisconnectEvent(DisconnectEvent notification) {
        // TODO Auto-generated method stub
        disconnect();
    }

    @Override
    public void onSwitchIdleEvent(SwitchIdleEvent notification) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean shouldBeDisconnected = true;

                final InetSocketAddress remoteAddress = connectionContext.getConnectionAdapter().getRemoteAddress();

                if (ConnectionContext.CONNECTION_STATE.WORKING.equals(connectionContext.getConnectionState())) {
                    LOG.debug(
                            "first idle state occured, node={}|auxId={}",
                            connectionContext.getConnectionAdapter().getRemoteAddress(),
                            connectionContext.getFeatures().getAuxiliaryId());
                    connectionContext.setConnectionState(ConnectionContext.CONNECTION_STATE.TIMEOUTING);
                    EchoInputBuilder builder = new EchoInputBuilder();
                    builder.setVersion(connectionContext.getFeatures().getVersion());
                    Xid xid = new Xid(0);
                    builder.setXid(xid.getValue());

                    Future<RpcResult<EchoOutput>> echoReplyFuture = connectionContext.getConnectionAdapter()
                            .echo(builder.build());

                    try {
                        RpcResult<EchoOutput> echoReplyValue = echoReplyFuture.get(MAX_ECHO_REPLY_TIMEOUT, TimeUnit.MILLISECONDS);
                        if (echoReplyValue.isSuccessful()) {
                            connectionContext.setConnectionState(ConnectionContext.CONNECTION_STATE.WORKING);
                            shouldBeDisconnected = false;
                        } else {
                            for (RpcError replyError : echoReplyValue
                                    .getErrors()) {
                                Throwable cause = replyError.getCause();
                                LOG.warn("while receiving echoReply [{}] in TIMEOUTING state {} ",
                                        remoteAddress,
                                        cause.getMessage());
                                LOG.trace("while receiving echoReply [{}] in TIMEOUTING state ..", remoteAddress, cause);
                            }
                        }
                    } catch (Exception e) {
                        LOG.warn("while waiting for echoReply in TIMEOUTING state: {}", e.getMessage());
                        LOG.trace("while waiting for echoReply in TIMEOUTING state ..", remoteAddress, e);
                    }
                }
                if (shouldBeDisconnected) {
                    disconnect();
                }
            }
        }).start();
    }

    private void disconnect() {

        LOG.trace("disconnecting: node={}|auxId={}|connection state = {}",
                connectionContext.getConnectionAdapter().getRemoteAddress(),
                connectionContext.getFeatures().getAuxiliaryId(),
                connectionContext.getConnectionState());

        ListenableFuture<Boolean> result = null;
        if (connectionContext.getConnectionAdapter().isAlive()) {
            result = JdkFutureAdapters.listenInPoolThread(connectionContext.getConnectionAdapter().disconnect());
        } else {
            LOG.debug("connection already disconnected");
            result = Futures.immediateFuture(true);
        }
        connectionContext.setConnectionState(ConnectionContext.CONNECTION_STATE.RIP);
        Futures.addCallback(result, new FutureCallback<Boolean>() {
            @Override
            public void onSuccess(final Boolean aBoolean) {
                LOG.debug("Connection node={}|auxId={}|connection state = {} closed.",
                        connectionContext.getConnectionAdapter().getRemoteAddress(),
                        connectionContext.getFeatures().getAuxiliaryId(),
                        connectionContext.getConnectionState());
            }

            @Override
            public void onFailure(final Throwable throwable) {
                LOG.debug("Connection node={}|auxId={}|connection state = {} close failed.",
                        connectionContext.getConnectionAdapter().getRemoteAddress(),
                        connectionContext.getFeatures().getAuxiliaryId(),
                        connectionContext.getConnectionState());
            }
        });

        connectionContext.propagateClosingConnection();
    }

}
