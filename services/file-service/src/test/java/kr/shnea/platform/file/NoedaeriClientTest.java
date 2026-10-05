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
}
