package io.github.tangbailin.novaorg.support;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
public final class StubProvider implements AutoCloseable {
    private final HttpServer server;
    public final List<JsonNode> requests=new CopyOnWriteArrayList<>();
    private final Queue<String> replies=new ConcurrentLinkedQueue<>();
    public StubProvider(String... responses) throws Exception {
        replies.addAll(List.of(responses));
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            try {
                requests.add(new ObjectMapper().readTree(exchange.getRequestBody()));
                String response=replies.poll();
                if(response==null) response="{\"error\":\"unexpected call\"}";
                byte[] bytes=response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type","application/json");
                exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
            } finally { exchange.close(); }
        });server.start();
    }
    public String url() { return "http://127.0.0.1:"+server.getAddress().getPort()+"/v1"; }
    @Override public void close() { server.stop(0); }
}
