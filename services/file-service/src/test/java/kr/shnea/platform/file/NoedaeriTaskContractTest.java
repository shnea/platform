package kr.shnea.platform.file;

import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class NoedaeriTaskContractTest {
    final JsonMapper json=new JsonMapper();
    @Test void requestsAreStrictAndScopesComeFromTheServer() {
        assertThat(NoedaeriTaskContract.options("stt.transcribe",json.readTree("{}"))).containsEntry("language","auto").containsEntry("use_itn",true);
        assertThat(NoedaeriTaskContract.options("pdf.extract",json.readTree("{\"mode\":\"ocr\"}"))).containsEntry("mode","ocr");
        for(String invalid:List.of("null","{\"use_itn\":\"true\"}","{\"language\":\"xx\"}","{\"callback_url\":\"https://example.invalid\"}"))
            assertThatThrownBy(()->NoedaeriTaskContract.options("stt.transcribe",json.readTree(invalid))).isInstanceOf(FileFailure.class);
        var context=new FileAccess.Context(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"ADMIN");
        var input=NoedaeriTaskContract.input("tts.synthesize",json.readTree("{\"text\":\"안녕하세요\"}"),null,context);
        assertThat(input).containsEntry("type","text").containsEntry("requester_id","admin:"+context.credentialId()).containsEntry("project",context.projectId().toString());
        assertThatThrownBy(()->NoedaeriTaskContract.input("tts.synthesize",json.readTree("{\"text\":\"안녕\",\"requester_id\":\"other\"}"),null,context)).isInstanceOf(FileFailure.class);
        assertThat(NoedaeriTaskContract.files("tts.synthesize")).containsExactly("speech.wav");
        assertThat(NoedaeriTaskContract.files("tts.voice.register")).containsExactly("reference.wav");
        assertThat(NoedaeriTaskContract.input("tts.voice.register",json.readTree("{\"name\":\"한국어 안내\",\"kind\":\"preset\",\"speaker\":\"Sohee\"}"),null,context)).containsEntry("requester_id",NoedaeriTaskContract.requester(context));
        assertThatThrownBy(()->NoedaeriTaskContract.input("tts.voice.register",json.readTree("{\"name\":\"한국어 안내\",\"kind\":\"preset\",\"speaker\":\"unknown\"}"),null,context)).isInstanceOf(FileFailure.class);
    }
    @Test void manifestsRejectMissingExtraOrEscapingArtifacts() throws Exception {
        NoedaeriTaskContract.manifest("stt.transcribe",json.readTree("{\"type\":\"stt_transcribe\",\"files\":[\"transcript.json\",\"transcript.txt\",\"transcript.zip\"]}"));
        for(String files:List.of("[]","[\"../transcript.json\"]","[\"transcript.json\",\"transcript.txt\",\"transcript.zip\",\"secret.txt\"]"))
            assertThatThrownBy(()->NoedaeriTaskContract.manifest("stt.transcribe",json.readTree("{\"type\":\"stt_transcribe\",\"files\":"+files+"}"))).isInstanceOf(java.io.IOException.class);
        NoedaeriTaskContract.manifest("tts.synthesize",json.readTree("{\"type\":\"artifact\",\"name\":\"speech.wav\",\"media_type\":\"audio/wav\"}"));
        assertThatThrownBy(()->NoedaeriTaskContract.manifest("tts.synthesize",json.readTree("{\"type\":\"artifact\",\"name\":\"speech.zip\",\"media_type\":\"application/zip\"}"))).isInstanceOf(java.io.IOException.class);
    }
    @Test void silentTranscriptsAreValidAndCoordinatesAndTimesAreChecked() throws Exception {
        assertThat(NoedaeriTaskContract.validate("stt.transcribe","transcript.json","{\"text\":\"\",\"duration_seconds\":1,\"segments\":[]}".getBytes()).path("text").asString()).isEmpty();
        NoedaeriTaskContract.validate("video.subtitles","subtitles.vtt","WEBVTT\n\n".getBytes());
        NoedaeriTaskContract.validate("video.subtitles","subtitles.srt",new byte[0]);
        assertThatThrownBy(()->NoedaeriTaskContract.validate("video.subtitles","subtitles.vtt","<script>bad</script>".getBytes())).isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(()->NoedaeriTaskContract.validate("stt.transcribe","transcript.json","{\"text\":\"bad\",\"duration_seconds\":1,\"segments\":[{\"start\":2,\"end\":1,\"text\":\"bad\"}]}".getBytes())).isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(()->NoedaeriTaskContract.validate("ocr.recognize","text.json","{\"text\":\"bad\",\"coordinate_system\":\"normalized_top_left\",\"lines\":[{\"text\":\"bad\",\"bounding_box\":{\"left\":-1,\"top\":0,\"width\":1,\"height\":1}}]}".getBytes())).isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(()->NoedaeriTaskContract.validate("tts.synthesize","speech.wav","not audio".getBytes())).isInstanceOf(java.io.IOException.class);
    }
}
