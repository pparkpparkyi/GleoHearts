package com.example.gleo_type.sync.security

import com.example.gleo_type.sync.ReviewExchange
import com.example.gleo_type.sync.ReviewBundleReceiver
import java.io.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** For a caller-approved, connected channel ONLY. Opens no socket/server, discovers no devices.
 * Caller owns bounded read/connect timeouts and close/cancel. Use exclusively on an IO worker. */
class FramedReviewTransport(private val input:InputStream,private val output:OutputStream):ReviewExchange,AutoCloseable {
    private val closed=java.util.concurrent.atomic.AtomicBoolean()
    @Synchronized override fun exchange(encryptedRequest:String):String {
        check(!closed.get()){ "Transport closed" }
        write(output,encryptedRequest);val response=read(input)
        check(!closed.get()){ "Transport cancelled before response acceptance" };return response
    }
    // Do not acquire the exchange monitor: close must be able to cancel a blocked read.
    override fun close(){if(closed.compareAndSet(false,true)){
        val first=runCatching{input.close()}.exceptionOrNull();val second=runCatching{output.close()}.exceptionOrNull()
        if(first!=null){second?.let{first.addSuppressed(it)};throw first};if(second!=null)throw second
    }}
    companion object {
        private const val MAGIC=0x47485231 // GHR1; wraps unchanged encrypted review/ACK v1 envelopes.
        const val MAX_BYTES=800000
        fun write(output:OutputStream,encrypted:String) {
            val bytes=encrypted.toByteArray(Charsets.UTF_8);require(bytes.size in 1..MAX_BYTES)
            DataOutputStream(output).apply{writeInt(MAGIC);writeInt(bytes.size);write(bytes);flush()}
        }
        fun read(input:InputStream):String {
            val stream=DataInputStream(input);require(stream.readInt()==MAGIC){"Invalid encrypted frame"}
            val length=stream.readInt();require(length in 1..MAX_BYTES){"Frame exceeds size bound"}
            val bytes=ByteArray(length);stream.readFully(bytes)
            return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        }
        /** Receiver performs authenticated durable inbox commit before generating the ACK frame. */
        fun receiveOne(input:InputStream,output:OutputStream,receiver:ReviewBundleReceiver) {
            val ack=receiver.receive(read(input));write(output,ack)
        }
    }
}
