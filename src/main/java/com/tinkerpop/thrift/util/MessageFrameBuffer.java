/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.tinkerpop.thrift.util;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.tinkerpop.thrift.TDisruptorServer;
import org.apache.thrift.TException;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.transport.TIOStreamTransport;
import org.apache.thrift.transport.TNonblockingTransport;
import org.apache.thrift.transport.TTransport;

/**
 * Possible states for the MessageFrameBuffer state machine.
 */
enum FrameBufferState
{
    // in the midst of reading the frame size off the wire
    READING_FRAME_SIZE,
    // reading the actual frame data now, but not all the way done yet
    READING_FRAME,
    // completely read the frame, so an invocation can now happen
    READ_FRAME_COMPLETE,
    // waiting to get switched to listening for write events
    AWAITING_REGISTER_WRITE,
    // started writing response data, not fully complete yet
    WRITING,
    // another thread wants this framebuffer to go back to reading
    AWAITING_REGISTER_READ,
    // we want our transport and selection key invalidated in the selector
    // thread
    AWAITING_CLOSE
}

/**
 * Class that implements a sort of state machine around the interaction with a
 * client and an invoker. It manages reading the frame size and frame data,
 * getting it handed off as wrapped transports, and then the writing of
 * response data back to the client. In the process it manages flipping the
 * read and write bits on the selection key for its client.
 */
public class MessageFrameBuffer
{
    private static final Logger logger = LoggerFactory.getLogger(MessageFrameBuffer.class);

    // the actual transport hooked up to the client.
    public final TNonblockingTransport transport;
    public final TDisruptorServer.ThriftFactories thriftFactories;

    // the SelectionKey that corresponds to our transport
    private final SelectionKey selectionKey;

    // where in the process of reading/writing are we?
    private FrameBufferState state = FrameBufferState.AWAITING_REGISTER_READ;

    private ByteBuffer buffer = null, frameSizeBuffer = ByteBuffer.allocate(4);
    private Memory backingMemory;

    private FastMemoryOutputStream response;

    public MessageFrameBuffer(TNonblockingTransport trans, SelectionKey key, TDisruptorServer.ThriftFactories factories)
    {
        transport = trans;
        selectionKey = key;
        thriftFactories = factories;
    }

    public boolean isReadyToRead()
    {
        return state == FrameBufferState.AWAITING_REGISTER_READ;
    }

    public boolean isReadyToWrite()
    {
        return state == FrameBufferState.AWAITING_REGISTER_WRITE;
    }

    /**
     * Give this MessageFrameBuffer a chance to read. The selector loop should have
     * received a read event for this MessageFrameBuffer.
     *
     * @return true if the connection should live on, false if it should be
     *         closed
     */
    public boolean read()
    {
        if (state == FrameBufferState.READING_FRAME_SIZE)
        {
            // try to read the frame size completely
            if (!internalReadFrameSize())
                return false;

            // if the frame size has been read completely, then prepare to read the
            // actual frame.
            if (frameSizeBuffer.remaining() == 0)
            {
                // pull out the frame size as an integer.
                int frameSize = frameSizeBuffer.getInt(0);

                if (frameSize <= 0)
                {
                    logger.error("Read an invalid frame size of " + frameSize + ". Are you using TFramedTransport on the client side?");
                    return false;
                }

                // reallocate to match frame size (if needed)
                reallocateBuffer(frameSize);

                state = FrameBufferState.READING_FRAME;
            }
            else
            {
                // this skips the check of READING_FRAME state below, since we can't
                // possibly go on to that state if there's data left to be read at
                // this one.
                return true;
            }
        }

        // it is possible to fall through from the READING_FRAME_SIZE section
        // to READING_FRAME if there's already some frame data available once
        // READING_FRAME_SIZE is complete.

        if (state == FrameBufferState.READING_FRAME)
        {
            if (!internalRead(buffer))
                return false;

            // since we're already in the select loop here for sure, we can just
            // modify our selection key directly.
            if (buffer.remaining() == 0)
            {
                // get rid of the read select interests
                selectionKey.interestOps(0);
                state = FrameBufferState.READ_FRAME_COMPLETE;
            }

            return true;
        }

        // if we fall through to this point, then the state must be invalid.
        logger.error("Read was called but state is invalid (" + state + ")");
        return false;
    }

    public boolean isFrameFullyRead()
    {
        return state == FrameBufferState.READ_FRAME_COMPLETE;
    }

    /**
     * Give this MessageFrameBuffer a chance to write its output to the final client.
     */
    public boolean write()
    {
        assert state == FrameBufferState.WRITING;

        try
        {
            if (transport.write(response.toByteBuffer()) < 0)
                return false;
        }
        catch (IOException e)
        {
            logger.warn("Got an IOException during write!", e);
            return false;
        }
        finally
        {
            response.close();
        }

        // we're done writing. Now we need to switch back to reading.
        switchToRead();

        return true;
    }

    /**
     * Give this MessageFrameBuffer a chance to set its interest to write, once data
     * has come in.
     */
    public void changeSelectInterests()
    {
        switch (state)
        {
            case AWAITING_REGISTER_WRITE: // set the OP_WRITE interest
                state = FrameBufferState.WRITING;
                break;

            case AWAITING_REGISTER_READ:
                prepareRead();
                break;

            case AWAITING_CLOSE:
                close();
                selectionKey.cancel();
                break;

            default:
                logger.error("changeSelectInterest was called, but state is invalid (" + state + ")");
        }
    }

    /**
     * After the processor has processed the invocation, whatever thread is
     * managing invocations should call this method on this MessageFrameBuffer so we
     * know it's time to start trying to write again. Also, if it turns out that
     * there actually isn't any data in the response buffer, we'll skip trying
     * to write and instead go back to reading.
     */
    public void responseReady()
    {
        // TODO: the read buffer is definitely no longer in use, so we will decrement
        // our read buffer count. we do this here as well as in close because
        // we'd like to free this read memory up as quickly as possible for other
        // clients.

        if (response.size() == 0)
        {
            // go straight to reading again. this was probably an one way method
            switchToRead();
        }
        else
        {
            // set state that we're waiting to be switched to write. we do this
            // asynchronously through requestSelectInterestChange() because there is
            // a possibility that we're not in the main thread, and thus currently
            // blocked in select(). (this functionality is in place for the sake of
            // the HsHa server.)
            switchToWrite();
        }
    }

    /**
     * Actually invoke the method signified by this MessageFrameBuffer.
     */
    public void invoke()
    {
        assert state == FrameBufferState.READ_FRAME_COMPLETE : "Invoke called is invalid state: " + state;

        TTransport inTrans = getInputTransport();
        TProtocol inProt = thriftFactories.inputProtocolFactory.getProtocol(inTrans);
        TProtocol outProt = thriftFactories.outputProtocolFactory.getProtocol(getOutputTransport());

        try
        {
            thriftFactories.processorFactory.getProcessor(inTrans).process(inProt, outProt);
            responseReady();
            return;
        }
        catch (TException te)
        {
            logger.warn("Exception while invoking!", te);
        }
        catch (Throwable t)
        {
            logger.error("Unexpected throwable while invoking!", t);
        }

        // This will only be reached when there is a throwable.
        state = FrameBufferState.AWAITING_CLOSE;
        changeSelectInterests();
    }

    /**
     * Wrap the read buffer in a memory-based transport so a processor can read
     * the data it needs to handle an invocation.
     */
    private TTransport getInputTransport()
    {
        return new TMemoryInputTransport(buffer);
    }

    /**
     * Get the transport that should be used by the invoker for responding.
     */
    private TTransport getOutputTransport()
    {
        response = new FastMemoryOutputStream();
        return thriftFactories.outputTransportFactory.getTransport(new TIOStreamTransport(response));
    }

    private boolean internalReadFrameSize()
    {
        frameSizeBuffer.clear();
        return internalRead(frameSizeBuffer);
    }

    /**
     * Perform a read into buffer.
     *
     * @return true if the read succeeded, false if there was an error or the
     *         connection closed.
     */
    private boolean internalRead(ByteBuffer buffer)
    {
        try
        {
            return !(transport.read(buffer) < 0);
        } catch (IOException e) {
            logger.warn("Got an IOException in internalRead!", e);
            return false;
        }
    }

    /**
     * We're done writing, so reset our interest ops and change state
     * accordingly.
     */
    private void prepareRead()
    {
        // get ready for another read-around
        state = FrameBufferState.READING_FRAME_SIZE;
    }

    private void switchToRead()
    {
        selectionKey.interestOps(SelectionKey.OP_READ);
        state = FrameBufferState.AWAITING_REGISTER_READ;
    }

    private void switchToWrite()
    {
        selectionKey.interestOps(SelectionKey.OP_WRITE);
        state = FrameBufferState.AWAITING_REGISTER_WRITE;
    }

    private void freeBuffer()
    {
        if (backingMemory == null || backingMemory.getPeer() == 0) // paranoia
            return;

        backingMemory.free();
        buffer = null;
    }

    private void reallocateBuffer(int newSize)
    {
        if (backingMemory == null)
        {
            backingMemory = Memory.allocate(newSize);
            buffer = backingMemory.toByteBuffer();
        }
        else if (buffer.capacity() != newSize)
        {
            backingMemory.reallocate(newSize);
            buffer = backingMemory.toByteBuffer();
        }

        buffer.clear();
    }

    /**
     * Shut the connection down.
     */
    public void close()
    {
        freeBuffer();
        transport.close();
    }
}