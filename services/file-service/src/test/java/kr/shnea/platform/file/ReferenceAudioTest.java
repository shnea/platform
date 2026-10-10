package kr.shnea.platform.file;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class ReferenceAudioTest {
    final JsonMapper json=new JsonMapper();
    @Test void threeSecondsAndShorterCannotRegisterButTheUpperBoundIsInclusive() {
        for(String seconds:List.of("0","-1","2.99","3","30.01","NaN","Infinity","invalid"))
            assertThatThrownBy(()->ReferenceAudio.validate(json.readTree("{\"streams\":[{\"codec_type\":\"audio\",\"duration\":\""+seconds+"\"}]}"))).isInstanceOf(FileFailure.class);
        for(String seconds:List.of("3.001","30"))
            ReferenceAudio.validate(json.readTree("{\"streams\":[{\"codec_type\":\"audio\",\"duration\":\""+seconds+"\"}]}"));
        assertThatThrownBy(()->ReferenceAudio.validate(json.readTree("{\"streams\":[{\"codec_type\":\"audio\",\"duration\":\"3\"}]}")))
            .hasMessageContaining("3초 이하");
    }
    @Test void containerDurationIsOnlyUsedWhenTheAudioTrackDurationIsUnavailable() {
        ReferenceAudio.validate(json.readTree("{\"streams\":[{\"codec_type\":\"audio\",\"duration\":\"N/A\"}],\"format\":{\"duration\":\"4\"}}"));
        ReferenceAudio.validate(json.readTree("{\"streams\":[{\"codec_type\":\"audio\"}],\"format\":{\"duration\":\"4\"}}"));
        for(String invalid:List.of("{}","{\"streams\":[]}","{\"streams\":[{\"codec_type\":\"video\"}],\"format\":{\"duration\":\"4\"}}",
                "{\"streams\":[{\"codec_type\":\"audio\"}]}",
                "{\"streams\":[{\"codec_type\":\"audio\",\"duration\":\"3\"}],\"format\":{\"duration\":\"10\"}}"))
            assertThatThrownBy(()->ReferenceAudio.validate(json.readTree(invalid))).isInstanceOf(FileFailure.class);
    }
}
