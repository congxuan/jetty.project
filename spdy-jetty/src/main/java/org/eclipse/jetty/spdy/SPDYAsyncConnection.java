/*
 * Copyright (c) 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.eclipse.jetty.spdy;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.AsyncEndPoint;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.nio.AsyncConnection;
import org.eclipse.jetty.io.nio.DirectNIOBuffer;
import org.eclipse.jetty.io.nio.IndirectNIOBuffer;
import org.eclipse.jetty.io.nio.NIOBuffer;
import org.eclipse.jetty.spdy.ISession.Controller;
import org.eclipse.jetty.spdy.api.SPDY;
import org.eclipse.jetty.spdy.api.SPDYException;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.parser.Parser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SPDYAsyncConnection extends AbstractConnection implements AsyncConnection, Controller<StandardSession.FrameBytes>
{
    private static final Logger logger = LoggerFactory.getLogger(SPDYAsyncConnection.class);
    private final Parser parser;
    private volatile Session session;
    private ByteBuffer readBuffer;
    private ByteBuffer writeBuffer;
    private Handler<StandardSession.FrameBytes> writeHandler;
    private StandardSession.FrameBytes writeContext;
    private volatile boolean writePending;

    public SPDYAsyncConnection(AsyncEndPoint endPoint, Parser parser)
    {
        super(endPoint);
        this.parser = parser;
        endPoint.setCheckForIdle(true);
    }

    @Override
    public Connection handle() throws IOException
    {
        AsyncEndPoint endPoint = getEndPoint();
        try
        {
            endPoint.setCheckForIdle(false);
            boolean progress = true;
            while (endPoint.isOpen() && progress)
            {
                int filled = fill();
                progress = filled > 0;

                int flushed = flush();
                progress |= flushed > 0;

                endPoint.flush();

                progress |= endPoint.hasProgressed();

                if (!progress && filled < 0)
                    close(false);
            }
            return this;
        }
        finally
        {
            endPoint.setCheckForIdle(true);
        }
    }

    public int fill() throws IOException
    {
        // In order to support reentrant parsing, we save the read buffer
        // so that reentrant calls can finish to consume the read buffer
        // or eventually read more bytes and parse them.

        int filled = 0;
        if (readBuffer == null)
        {
            // TODO: use buffer pool ?
            NIOBuffer jettyBuffer = new DirectNIOBuffer(4096);
            AsyncEndPoint endPoint = getEndPoint();
            filled = endPoint.fill(jettyBuffer);
            logger.debug("Filled {} from {}", filled, endPoint);
            if (filled <= 0)
                return filled;

            ByteBuffer buffer = jettyBuffer.getByteBuffer();
            buffer.limit(jettyBuffer.putIndex());
            buffer.position(jettyBuffer.getIndex());
            this.readBuffer = buffer;
        }
        parser.parse(readBuffer);
        readBuffer = null;
        return filled;
    }

    public int flush()
    {
        int result = 0;
        // Volatile read to ensure visibility of buffer and handler
        if (writePending)
            result = write(writeBuffer, writeHandler, writeContext);
        logger.debug("Flushed {} to {}", result, getEndPoint());
        return result;
    }

    @Override
    public int write(ByteBuffer buffer, ISession.Controller.Handler<StandardSession.FrameBytes> handler, StandardSession.FrameBytes context)
    {
        int remaining = buffer.remaining();
        Buffer jettyBuffer = buffer.isDirect() ? new DirectNIOBuffer(buffer, false) : new IndirectNIOBuffer(buffer, false);
        AsyncEndPoint endPoint = getEndPoint();
        try
        {
            int written = endPoint.flush(jettyBuffer);
            logger.debug("Written {} bytes, {} remaining", written, jettyBuffer.length());
        }
        catch (IOException x)
        {
            close(false);
            throw new SPDYException(x);
        }
        finally
        {
            buffer.limit(jettyBuffer.putIndex());
            buffer.position(jettyBuffer.getIndex());
        }

        if (buffer.hasRemaining())
        {
            // Save buffer and handler in order to finish the write later in flush()
            this.writeBuffer = buffer;
            this.writeHandler = handler;
            this.writeContext = context;
            // Volatile write to ensure visibility of write fields
            writePending = true;
            endPoint.scheduleWrite();
        }
        else
        {
            if (writePending)
            {
                this.writeBuffer = null;
                this.writeHandler = null;
                this.writeContext = null;
                // Volatile write to ensure visibility of write fields
                writePending = false;
            }
            handler.complete(context);
        }

        return remaining - buffer.remaining();
    }

    @Override
    public void close(boolean onlyOutput)
    {
        try
        {
            AsyncEndPoint endPoint = getEndPoint();
            if (onlyOutput)
            {
                try
                {
                    logger.debug("Shutting down output {}", endPoint);
                    endPoint.shutdownOutput();
                }
                catch (IOException x)
                {
                    endPoint.close();
                }
            }
            else
            {
                logger.debug("Closing {}", endPoint);
                endPoint.close();
            }
        }
        catch (IOException x)
        {
            logger.trace("", x);
        }
    }

    @Override
    public AsyncEndPoint getEndPoint()
    {
        return (AsyncEndPoint) super.getEndPoint();
    }

    @Override
    public boolean isIdle()
    {
        return false;
    }

    @Override
    public boolean isSuspended()
    {
        return false;
    }

    @Override
    public void onClose()
    {
        // TODO
    }

    @Override
    public void onInputShutdown() throws IOException
    {
        // TODO
    }

    @Override
    public void onIdleExpired(long idleForMs)
    {
        session.goAway(SPDY.V2);
    }

    protected Session getSession()
    {
        return session;
    }

    protected void setSession(Session session)
    {
        this.session = session;
    }
}
