package com.apiforge.config;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.apiforge.common.error.ErrorCode;
import com.apiforge.common.error.ProblemResponseWriter;
import com.apiforge.common.web.PayloadTooLargeException;

/**
 * Caps the size of every request body. Declared sizes (Content-Length) are rejected up front;
 * bodies without a declared size (chunked) are counted while being read and fail once they
 * exceed the cap. The largest legitimate body is an OpenAPI document, so the cap is the
 * specification limit plus room for JSON escaping and multipart framing.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    private final long maxBytes;
    private final ProblemResponseWriter problemWriter;

    public RequestSizeLimitFilter(ApiForgeProperties properties, ProblemResponseWriter problemWriter) {
        long spec = properties.specification().maxSize().toBytes();
        this.maxBytes = spec + spec / 5 + 64 * 1024;
        this.problemWriter = problemWriter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long declared = request.getContentLengthLong();
        if (declared > maxBytes) {
            problemWriter.write(request, response, ErrorCode.PAYLOAD_TOO_LARGE,
                    "The request body is too large (maximum %d MB).".formatted(Math.max(1, maxBytes / (1024 * 1024))));
            return;
        }
        chain.doFilter(declared >= 0 ? request : new LimitedRequest(request, maxBytes), response);
    }

    long maxBytes() {
        return maxBytes;
    }

    private static final class LimitedRequest extends HttpServletRequestWrapper {

        private final long limit;
        private ServletInputStream stream;

        LimitedRequest(HttpServletRequest request, long limit) {
            super(request);
            this.limit = limit;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                stream = new CountingStream(super.getInputStream(), limit);
            }
            return stream;
        }
    }

    private static final class CountingStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private final long limit;
        private long count;

        CountingStream(ServletInputStream delegate, long limit) {
            this.delegate = delegate;
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value >= 0) {
                advance(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = delegate.read(buffer, offset, length);
            if (read > 0) {
                advance(read);
            }
            return read;
        }

        private void advance(long bytes) throws PayloadTooLargeException {
            count += bytes;
            if (count > limit) {
                throw new PayloadTooLargeException(limit);
            }
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            delegate.setReadListener(listener);
        }
    }
}
