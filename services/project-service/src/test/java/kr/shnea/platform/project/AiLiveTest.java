package kr.shnea.platform.project;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.*;

/** Opt-in: generated text only; no platform runtime DB, originals or decrypted env files. */
@EnabledIfEnvironmentVariable(named="AI_LIVE_CHECK", matches="true")
@Timeout(150)
class AiLiveTest {
    AiGateway client() { return new AiGateway(System.getenv("NOEDAERI_BASE_URL"), System.getenv("NOEDAERI_PLATFORM_API_KEY")); }
    @Test void realSingleAndBatchEmbeddingsMatchTheCommonContract() {
        var ai = client();
        var single = ai.embeddings("{\"input\":\"플랫폼 공통 임베딩 연결 검수\"}".getBytes(StandardCharsets.UTF_8));
        assertThat(single.path("dimensions").asInt()).isEqualTo(768);
        assertThat(single.path("data").size()).isEqualTo(1);
        var batch = ai.embeddings("{\"input\":[\"프로젝트별 서버 키\",\"기존 OIDC 로그인 유지\"],\"dimensions\":768}".getBytes(StandardCharsets.UTF_8));
        assertThat(batch.path("data").size()).isEqualTo(2);
    }
    @Test void realRayaKeepsItsPredictionAndRuntimeContract() {
        var result = client().route("{\"task_type\":\"chat.general\",\"prompt\":\"한 문장으로 인사해 주세요.\",\"instruction\":\"\",\"has_images\":false}".getBytes(StandardCharsets.UTF_8));
        assertThat(result.path("model_tier").asText()).isIn("L1", "L2", "L3");
        assertThat(result.path("device").asText()).isEqualTo("cpu");
    }
}
