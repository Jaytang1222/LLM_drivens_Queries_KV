package kart.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

final class StructuredChoiceClientTest {
  @Test void schemaIsOptInAndDoesNotChangeLegacyRequests() throws Exception {
    ObjectMapper mapper=new ObjectMapper();
    AtomicReference<JsonNode> received=new AtomicReference<JsonNode>();
    HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/v1/chat/completions",exchange->{
      received.set(mapper.readTree(exchange.getRequestBody()));
      byte[] response="{\"choices\":[{\"message\":{\"content\":\"K\"}}]}".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200,response.length);
      exchange.getResponseBody().write(response);exchange.close();
    });
    server.start();
    try {
      OpenAiCompatibleClient client=new OpenAiCompatibleClient("http://127.0.0.1:"+server.getAddress().getPort()+"/v1","test","test",false);
      JsonNode schema=mapper.readTree("{\"type\":\"string\",\"enum\":[\"K\",\"0\"]}");
      LlmOptions opts=LlmOptions.oneShot(2000);
      client.chat(Collections.singletonList(LlmMessage.user("choose")),schema,opts);
      assertFalse(received.get().has("response_format"));
      opts.responseSchemaFormat=true;
      client.chat(Collections.singletonList(LlmMessage.user("choose")),schema,opts);
      assertEquals("json_schema",received.get().path("response_format").path("type").asText());
      assertEquals(schema,received.get().path("response_format").path("json_schema").path("schema"));
    } finally {server.stop(0);}
  }
}
