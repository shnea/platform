package kr.shnea.platform.project;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AiCompletionTest {
    @Test void signatureCoversRawBytesAndRequiresFreshTimestampAndSharedSecret() throws Exception {
        byte[] body = "{\"event_id\":\"example\"}".getBytes(StandardCharsets.UTF_8);
        var mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec("test-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update("1000.".getBytes(StandardCharsets.US_ASCII));
        String signature = "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
        AiCompletion.verify(body, "1000", signature, "test-secret", 1300);
        for (long now : new long[] {699,1301})
            assertThatThrownBy(() -> AiCompletion.verify(body, "1000", signature, "test-secret", now)).isInstanceOf(ApiCode.Failure.class);
        assertThatThrownBy(() -> AiCompletion.verify("{}".getBytes(StandardCharsets.UTF_8), "1000", signature, "test-secret", 1000)).isInstanceOf(ApiCode.Failure.class);
        assertThatThrownBy(() -> AiCompletion.verify(body, "1000", signature, "other-secret", 1000)).isInstanceOf(ApiCode.Failure.class);
        assertThatThrownBy(() -> AiCompletion.verify(body, "1000", signature, "", 1000)).isInstanceOf(ApiCode.Failure.class);
        assertThatThrownBy(() -> AiCompletion.verify(body, "1000.0", signature, "test-secret", 1000)).isInstanceOf(ApiCode.Failure.class);
    }
}
