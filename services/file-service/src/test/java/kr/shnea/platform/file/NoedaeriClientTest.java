package kr.shnea.platform.file;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class NoedaeriClientTest {
    @Test void signatureUsesOriginalBytesAndRejectsTamperingStaleTimeAndMissingHeaders() throws Exception {
        byte[] body="{ \"version\": 1 }".getBytes(StandardCharsets.UTF_8);String secret="shared-test-secret";
        long now=Instant.now().getEpochSecond();String timestamp=Long.toString(now);
        var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        mac.update((timestamp+".").getBytes(StandardCharsets.US_ASCII));String signature="sha256="+HexFormat.of().formatHex(mac.doFinal(body));
        NoedaeriWebhook.verify(body,timestamp,signature,secret,now);
        assertThatThrownBy(()->NoedaeriWebhook.verify("{\"version\":1}".getBytes(),timestamp,signature,secret,now)).isInstanceOf(FileFailure.class);
        assertThatThrownBy(()->NoedaeriWebhook.verify(body,timestamp,signature,secret,now+301)).isInstanceOf(FileFailure.class);
        assertThatThrownBy(()->NoedaeriWebhook.verify(body,null,signature,secret,now)).isInstanceOf(FileFailure.class);
        assertThatThrownBy(()->NoedaeriWebhook.verify(body,timestamp,null,secret,now)).isInstanceOf(FileFailure.class);
    }
    @Test void productionConfigurationRequiresHttpsAndRejectsCredentialsAndRedirectOrigins() {
        assertThatThrownBy(()->new NoedaeriClient("http://example.invalid","key","secret",512)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new NoedaeriClient("https://user:password@example.invalid","key","secret",512)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new NoedaeriClient("https://example.invalid/?url=https://other.invalid","key","secret",512)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new NoedaeriClient("","","",512).configured()).isFalse();
        assertThatThrownBy(()->new NoedaeriClient("https://example.invalid","key","secret",FilesService.MAX_FILE+1)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectedDownloadsNeverDeleteExistingFilesAndPartialDownloadsAreRemoved(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/v1/jobs",exchange->{exchange.sendResponseHeaders(200,0);exchange.getResponseBody().write(new byte[100]);exchange.close();});server.start();
        try {
            var client=new NoedaeriClient(java.net.URI.create("http://127.0.0.1:"+server.getAddress().getPort()),"key","secret",100000);
            var existing=directory.resolve("existing.wav");java.nio.file.Files.writeString(existing,"preserve");
            assertThatThrownBy(()->client.downloadResult(java.util.UUID.randomUUID(),existing,200)).isInstanceOf(java.io.IOException.class);
            assertThat(java.nio.file.Files.readString(existing)).isEqualTo("preserve");
            var partial=directory.resolve("partial.wav");assertThatThrownBy(()->client.downloadResult(java.util.UUID.randomUUID(),partial,10)).isInstanceOf(java.io.IOException.class);
            assertThat(java.nio.file.Files.exists(partial)).isFalse();
            assertThatThrownBy(()->client.downloadTaskFile(java.util.UUID.randomUUID(),"stt.transcribe","../speech.wav",partial)).isInstanceOf(java.io.IOException.class);
        }finally{server.stop(0);}
    }
}
