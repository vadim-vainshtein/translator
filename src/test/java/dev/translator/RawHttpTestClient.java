package dev.translator;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

final class RawHttpTestClient {
    private RawHttpTestClient() {}

    static Socket partial(int port, String path, String headers) throws Exception {
        var socket = new Socket("127.0.0.1", port);
        socket.setSoTimeout(4000);
        String request =
                "POST "
                        + path
                        + " HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\n"
                        + headers
                        + "\r\n{";
        socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        return socket;
    }

    static void sendTranslation(Socket socket, String authorization, String request)
            throws Exception {
        byte[] body = request.getBytes(StandardCharsets.UTF_8);
        String headers =
                "POST /v1/translations HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer "
                        + authorization
                        + "\r\nContent-Type: application/json\r\nContent-Length: "
                        + body.length
                        + "\r\n\r\n";
        var output = socket.getOutputStream();
        output.write(headers.getBytes(StandardCharsets.US_ASCII));
        output.write(body);
        output.flush();
    }

    static void assertSuccessfulResponse(InputStream input) throws Exception {
        assertThat(httpLine(input)).contains("200");
        int length = -1;
        boolean chunked = false;
        String header;
        while (!(header = httpLine(input)).isEmpty()) {
            if (header.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                length = Integer.parseInt(header.substring(15).trim());
            }
            if (header.equalsIgnoreCase("Transfer-Encoding: chunked")) {
                chunked = true;
            }
        }
        if (chunked) {
            assertChunkedBody(input);
        } else {
            assertThat(length).isGreaterThanOrEqualTo(0);
            assertThat(input.readNBytes(length)).hasSize(length);
        }
    }

    private static void assertChunkedBody(InputStream input) throws Exception {
        int chunk;
        while ((chunk = Integer.parseInt(httpLine(input), 16)) > 0) {
            assertThat(input.readNBytes(chunk)).hasSize(chunk);
            assertThat(httpLine(input)).isEmpty();
        }
        assertThat(httpLine(input)).isEmpty();
    }

    private static String httpLine(InputStream input) throws Exception {
        var line = new ByteArrayOutputStream();
        for (int ch; (ch = input.read()) != '\n'; ) {
            if (ch == -1) {
                throw new EOFException();
            }
            if (ch != '\r') {
                line.write(ch);
            }
        }
        return line.toString(StandardCharsets.US_ASCII);
    }

    static String statusLine(Socket socket) throws Exception {
        return new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))
                .readLine();
    }
}
