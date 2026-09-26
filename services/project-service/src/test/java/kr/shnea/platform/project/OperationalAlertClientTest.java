package kr.shnea.platform.project;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OperationalAlertClientTest {
    @Test void bridgesAuthoritativeEnvironmentAndActorAndMapsNotFound() throws Exception {
        UUID project=UUID.randomUUID(),env=UUID.randomUUID(),id=UUID.randomUUID();
        var projects=mock(ProjectService.class);
        when(projects.findEnvironment(env)).thenReturn(new ProjectService.Environment(env,project,"dev","DEV","realm",false,List.of(),"READY","issuer",1));
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var body=new AtomicReference<>("");var query=new AtomicReference<>("");var status=new AtomicInteger(200);
        server.createContext("/internal/v1/operational-alerts",exchange->{
            assertThat(exchange.getRequestHeaders().getFirst("X-Platform-Event-Key")).isEqualTo("secret");
            query.set(exchange.getRequestURI().getQuery());body.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            String result=exchange.getRequestMethod().equals("GET") ? "[]" : "{\"id\":\""+id+"\"}";
            byte[] bytes=result.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(status.get(),bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        try {
            var controller=new OperationalAlertController(projects,"secret","http://127.0.0.1:"+server.getAddress().getPort());
            assertThat(controller.list(env,false,21,20)).isEmpty();
            assertThat(query.get()).contains("projectId="+project,"environmentId="+env,"acknowledged=false","limit=21","offset=20");
            var actor=Jwt.withTokenValue("test").header("alg","RS256").subject("real-admin").build();
            controller.acknowledge(env,id,actor,new MockHttpServletRequest());
            var sent=new JsonMapper().readTree(body.get());assertThat(sent.path("actor").asText()).isEqualTo("real-admin");
            assertThat(sent.path("projectId").asText()).isEqualTo(project.toString());assertThat(sent.path("requestId").asText()).matches("[a-f0-9]{32}");
            status.set(404);
            assertThatThrownBy(()->controller.acknowledge(env,id,actor,new MockHttpServletRequest())).isInstanceOf(ResponseStatusException.class)
                .satisfies(e->assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(404));
            assertThatThrownBy(()->controller.list(env,false,0,0)).isInstanceOf(ResponseStatusException.class);
        }finally {server.stop(0);}
    }
}
