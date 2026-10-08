package com.blueai.ai.task.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.*;

@Component
public class AiTaskRequestSizeFilter extends OncePerRequestFilter {
    private static final int MAX_BYTES = 64 * 1024;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(request.getContextPath() + "/api/v1/ai/tasks");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > MAX_BYTES) {
            tooLarge(response);
            return;
        }
        chain.doFilter(new LimitedRequest(request), response);
    }

    private void tooLarge(HttpServletResponse response) throws IOException {
        response.setStatus(413);
        response.setContentType("application/json");
        response.getWriter()
                .write(
                        "{\"error\":{\"code\":\"REQUEST_TOO_LARGE\",\"message\":\"AI task request"
                            + " exceeds 64 KiB.\"}}");
    }

    private static final class LimitedRequest extends HttpServletRequestWrapper {
        private ServletInputStream stream;
        private BufferedReader reader;

        LimitedRequest(HttpServletRequest request) {
            super(request);
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (reader != null) throw new IllegalStateException("getReader already called");
            if (stream == null) {
                ServletInputStream original = super.getInputStream();
                stream =
                        new ServletInputStream() {
                            int count;

                            @Override
                            public int read() throws IOException {
                                int b = original.read();
                                if (b >= 0 && ++count > MAX_BYTES)
                                    throw new IOException("AI_TASK_REQUEST_TOO_LARGE");
                                return b;
                            }

                            @Override
                            public int read(byte[] b, int off, int len) throws IOException {
                                int n = original.read(b, off, Math.min(len, MAX_BYTES - count + 1));
                                if (n > 0) {
                                    count += n;
                                    if (count > MAX_BYTES)
                                        throw new IOException("AI_TASK_REQUEST_TOO_LARGE");
                                }
                                return n;
                            }

                            @Override
                            public boolean isFinished() {
                                return original.isFinished();
                            }

                            @Override
                            public boolean isReady() {
                                return original.isReady();
                            }

                            @Override
                            public void setReadListener(ReadListener listener) {
                                original.setReadListener(listener);
                            }
                        };
            }
            return stream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            if (stream != null) throw new IllegalStateException("getInputStream already called");
            if (reader == null)
                reader =
                        new BufferedReader(
                                new InputStreamReader(
                                        getInputStream(),
                                        getCharacterEncoding() == null
                                                ? java.nio.charset.StandardCharsets.UTF_8
                                                : java.nio.charset.Charset.forName(
                                                        getCharacterEncoding())));
            return reader;
        }
    }
}
