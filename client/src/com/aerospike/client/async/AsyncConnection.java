/*
 * Copyright 2012-2015 Aerospike, Inc.
 *
 * Portions may be licensed to Aerospike, Inc. under one or more contributor
 * license agreements WHICH ARE COMPATIBLE WITH THE APACHE LICENSE, VERSION 2.0.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.aerospike.client.async;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;

import com.aerospike.client.AerospikeException;
import com.aerospike.client.Log;
import com.aerospike.client.util.Util;

/**
 * Asynchronous socket channel connection wrapper.
 */
public final class AsyncConnection implements Closeable{
	private final SocketChannel socketChannel;
	private final SelectorManager manager;
	private SelectionKey key;
	private final long maxSocketIdleMillis;
	private volatile long lastUsed;
	
	public AsyncConnection(InetSocketAddress address, AsyncCluster cluster) throws AerospikeException.Connection {
		this.manager = cluster.getSelectorManager();
		this.maxSocketIdleMillis = cluster.getMaxSocketIdleMillis();
		
		try {
			socketChannel = SocketChannel.open();
		}
		catch (Exception e) {
			throw new AerospikeException.Connection("SocketChannel open error: " + e.getMessage());
		}

		try {
			socketChannel.configureBlocking(false);
			Socket socket = socketChannel.socket();
			socket.setTcpNoDelay(true);
			
			// These options are useful when the connection pool is poorly bounded or there are a large
			// amount of network errors.  Since these conditions are not the normal use case and
			// the options could theoretically result in latent data being sent to new commands, leave
			// them out for now.
			// socket.setReuseAddress(true);
			// socket.setSoLinger(true, 0);
			
			if (socketChannel.connect(address)) {
				lastUsed = System.currentTimeMillis();
			}
		}
		catch (Exception e) {
			close();
			throw new AerospikeException.Connection("SocketChannel init error: " + e.getMessage());
		}
	}
	
	public void execute(AsyncCommand command) {
		manager.execute(command);
	}

	public void finishConnect() throws IOException {		
		socketChannel.finishConnect();
		lastUsed = System.currentTimeMillis();
		key.interestOps(SelectionKey.OP_WRITE);
	}
	
	public void register(AsyncCommand command, Selector selector) throws ClosedChannelException {
		if (key != null && key.isValid()) {
			key.attach(command);
			key.interestOps(SelectionKey.OP_WRITE);
		}
		else {
			key = socketChannel.register(selector, SelectionKey.OP_CONNECT, command);    		
		}
    }
    
    public void unregister() {
    	key.interestOps(0);
    	key.attach(null);
    }

    public void write(ByteBuffer byteBuffer) throws IOException {
    	/*
    	// Temporary size check for corrupted buffer.
		long proto = byteBuffer.getLong(0);
		int protoSize = ((int) (proto & 0xFFFFFFFFFFFFL)) + 8;
		int bufferSize = byteBuffer.limit();
		
		if (bufferSize != protoSize) {
			Log.warn("Socket buffer size " + bufferSize + " not equal to proto size " + protoSize);
		}
		*/

		socketChannel.write(byteBuffer);
    	
		if (! byteBuffer.hasRemaining()) {
			byteBuffer.clear();
			byteBuffer.limit(8);
			key.interestOps(SelectionKey.OP_READ);
		}
    }
    
    public void setReadable() {   	
		key.interestOps(SelectionKey.OP_READ);
		manager.wakeup();
    }

    /**
     * Read till byteBuffer limit reached or received would-block.
     */
    public boolean read(ByteBuffer byteBuffer) throws IOException {
		while (byteBuffer.hasRemaining()) {
			int len = socketChannel.read(byteBuffer);
			
			if (len == 0) {			
				// Got would-block.
				return false;
			}
			
			if (len < 0) {
				// Server has shutdown socket.
		    	throw new EOFException();
			}
		}
		return true;
    }

	/**
	 * Is socket open and used within specified limits.
	 */
	public boolean isValid() {
		// Since lastUsed == 0 indicates closed connection, there is no need to
		// check socketChannel.isOpen() or socketChannel.isConnected() (which is unreliable)
		// because System.currentTimeMillis() will always be greater than maxSocketIdleMillis.
		return (System.currentTimeMillis() - lastUsed) <= maxSocketIdleMillis;
	}
	
	public void updateLastUsed() {
		lastUsed = System.currentTimeMillis();
	}

	public int interestOps() {
		return key.interestOps();
	}
	
	/**
	 * Close socket channel.
	 */
	public void close() {
		lastUsed = 0;
		
		if (key != null) {
			key.cancel();
		}
		
		try {
			socketChannel.close();
		}
		catch (Exception e) {
			if (Log.debugEnabled()) {
				Log.debug("Error closing socket: " + Util.getErrorMessage(e));
			}
		}
	}
}
