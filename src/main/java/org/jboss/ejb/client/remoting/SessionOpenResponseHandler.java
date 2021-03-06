/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2011, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.ejb.client.remoting;

import org.jboss.ejb.client.EJBReceiverInvocationContext;
import org.jboss.ejb.client.SessionID;
import org.jboss.logging.Logger;
import org.jboss.remoting3.MessageInputStream;

import java.io.DataInputStream;
import java.io.IOException;

/**
 * Responsible for parsing a stream for a (prior) session open request's response, as per the EJB remote client protocol
 * <p/>
 * User: Jaikiran Pai
 */
class SessionOpenResponseHandler extends ProtocolMessageHandler {

    private static final Logger logger = Logger.getLogger(SessionOpenResponseHandler.class);

    private final ChannelAssociation channelAssociation;

    SessionOpenResponseHandler(final ChannelAssociation channelAssociation) {
        this.channelAssociation = channelAssociation;
    }

    /**
     * Processes the passed <code>MessageInputStream</code> for a session id response, for a (prior) session open request
     * This method does <i>not</i> fully read the passed stream, instead it reads enough to create a {@link org.jboss.ejb.client.EJBReceiverInvocationContext.ResultProducer}
     * which can then read the rest of the stream when the {@link org.jboss.ejb.client.EJBReceiverInvocationContext.ResultProducer#getResult()} is called
     *
     * @param messageInputStream The message input stream
     * @throws IOException If there's a problem reading the stream
     */
    @Override
    protected void processMessage(final MessageInputStream messageInputStream) throws IOException {
        if (messageInputStream == null) {
            throw new IllegalArgumentException("Cannot read from null stream");
        }
        final DataInputStream input = new DataInputStream(messageInputStream);
        // read the invocation id
        final short invocationId = input.readShort();
        // create a ResultProducer which can be used to get the session id result
        final EJBReceiverInvocationContext.ResultProducer resultProducer = new SessionIDResultProducer(input);
        this.channelAssociation.resultReady(invocationId, resultProducer);
    }

    /**
     * A result producer which reads a input stream to return a session id as a result
     */
    private class SessionIDResultProducer implements EJBReceiverInvocationContext.ResultProducer {

        private final DataInputStream input;

        SessionIDResultProducer(final DataInputStream input) {
            this.input = input;
        }

        @Override
        public Object getResult() throws Exception {
            try {
                // read the session id length
                final int sessionIdLength = PackedInteger.readPackedInteger(input);
                final byte[] sessionIdBytes = new byte[sessionIdLength];
                // read the session id
                this.input.read(sessionIdBytes);
                // read the attachments
                final RemotingAttachments attachments = SessionOpenResponseHandler.this.readAttachments(input);
                return SessionID.createSessionID(sessionIdBytes);
            } finally {
                this.input.close();
            }
        }

        @Override
        public void discardResult() {
            //To change body of implemented methods use File | Settings | File Templates.
        }
    }
}
