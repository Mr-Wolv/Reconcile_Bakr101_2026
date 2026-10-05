package com.reconcile.api;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * A request whose body is read at most {@code limit + 1} bytes, and never more.
 *
 * <p>Written to fix F-04. The webhook's size contract was enforced against {@code Content-Length},
 * which a chunked request does not have, and the fallback read the body with {@code readAllBytes()}
 * <i>before</i> deciding it had been truncated. So a 300 KB chunked request was fully received,
 * fully allocated, and then refused — the documented bound was real for a declared length and
 * imaginary for an undeclared one. On an unauthenticated endpoint, that is the difference between a
 * size limit and a suggestion.
 *
 * <p>The fix is to make the bound part of <b>reading</b> rather than part of checking. This stream
 * reads up to {@code limit} bytes into the cache and then takes at most one more byte to decide
 * whether the body was exactly at the limit or over it. It never asks the underlying stream for
 * more, so a client that announces nothing and sends a gigabyte costs this process one byte of it.
 *
 * <p><b>Why the extra byte.</b> {@code limit} bytes and {@code limit + 1} bytes are both legal under
 * "no longer than the limit", and they are indistinguishable if you stop at the limit. Reading one
 * byte past it is what makes "exactly at the limit" an accepted request rather than a rejected one.
 *
 * <p><b>On not draining the rest.</b> When the body overflows, the remaining bytes are never read
 * and the connection cannot be reused. That is the intended trade: the alternative is consuming an
 * unbounded body in order to be polite to the next request on the socket, which is the very cost
 * this class exists to avoid. The container closes the connection, which is the correct answer for a
 * request that has already violated the documented contract.
 */
public class BoundedRequestWrapper extends HttpServletRequestWrapper {

    private final int limit;

    private byte[] cached;
    private boolean overflowed;

    public BoundedRequestWrapper(HttpServletRequest request, int limit) {
        super(request);
        if (limit < 0) {
            throw new IllegalArgumentException("limit must not be negative: " + limit);
        }
        this.limit = limit;
    }

    /** The bytes read so far, or empty when the body exceeded the limit. */
    public Optional<byte[]> body() {
        readIfNeeded();
        return overflowed ? Optional.empty() : Optional.of(cached);
    }

    /** Whether the body was refused for exceeding {@code limit}. */
    public boolean overflowed() {
        readIfNeeded();
        return overflowed;
    }

    /**
     * Reads at most {@code limit + 1} bytes, once.
     *
     * <p>Idempotent: the first read consumes the underlying stream, so a second must return the
     * same answer rather than find the stream exhausted and report an empty body.
     */
    private synchronized void readIfNeeded() {
        if (cached != null) {
            return;
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(Math.min(limit, 8192));
        byte[] scratch = new byte[8192];
        int remaining = limit;
        try (ServletInputStream in = getRequest().getInputStream()) {
            while (remaining > 0) {
                int read = in.read(scratch, 0, Math.min(scratch.length, remaining));
                if (read < 0) {
                    break;
                }
                buffer.write(scratch, 0, read);
                remaining -= read;
            }
            if (remaining == 0) {
                // The limit is reached. One more byte decides whether the body stops here legally
                // or continues past what we are willing to read.
                int extra = in.read();
                overflowed = extra >= 0;
            }
        } catch (IOException unreadable) {
            throw new IllegalStateException("the request body could not be read", unreadable);
        }
        cached = buffer.toByteArray();
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
        // A caller reading the stream directly gets the bounded bytes, so the bound cannot be
        // bypassed by reading the request instead of asking the wrapper for its cache.
        readIfNeeded();
        return new CachedServletInputStream(cached, overflowed);
    }

    @Override
    public BufferedReader getReader() throws IOException {
        readIfNeeded();
        return new BufferedReader(new InputStreamReader(
                new CachedServletInputStream(cached, overflowed), charset()));
    }

    /**
     * The request's declared charset, defaulting to UTF-8.
     *
     * <p>Read defensively: {@code getCharacterEncoding()} returns null when the client sent no
     * charset, and {@code getReader} is documented to honour the encoding rather than throw.
     */
    private Charset charset() {
        String declared = getRequest().getCharacterEncoding();
        return (declared == null || declared.isBlank())
                ? StandardCharsets.UTF_8
                : Charset.forName(declared);
    }

    /** Serves a byte array as a stream, and refuses to pretend it holds more than it does. */
    private static final class CachedServletInputStream extends ServletInputStream {

        private final byte[] bytes;
        private final boolean overflowed;
        private int position;

        private CachedServletInputStream(byte[] bytes, boolean overflowed) {
            this.bytes = bytes;
            this.overflowed = overflowed;
        }

        @Override
        public int read() {
            if (position >= bytes.length) {
                return -1;
            }
            return bytes[position++] & 0xFF;
        }

        @Override
        public int read(byte[] into, int offset, int length) {
            if (position >= bytes.length) {
                return -1;
            }
            int count = Math.min(length, bytes.length - position);
            System.arraycopy(bytes, position, into, offset, count);
            position += count;
            return count;
        }

        @Override
        public int available() {
            return bytes.length - position;
        }

        @Override
        public boolean isFinished() {
            return position >= bytes.length;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            throw new UnsupportedOperationException("the bounded body is fully buffered; "
                    + "asynchronous reads are not supported");
        }

        @Override
        public void close() {
            // Nothing to release: the bytes are already in memory and the underlying stream is
            // closed by readIfNeeded.
        }

        /** Whether the underlying body was over the limit; exposed for the controller's message. */
        boolean overflowed() {
            return overflowed;
        }
    }
}