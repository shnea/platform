package kr.shnea.platform.file;

import java.nio.file.Path;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

final class ReferenceAudio {
    private ReferenceAudio() {}
    static void validate(Path path) throws Exception {
        JsonNode metadata;
        try {
            byte[] bytes=FileViews.run(List.of("ffprobe","-v","error","-max_alloc","67108864","-protocol_whitelist","file",
                "-format_whitelist","wav,mp3,flac,ogg,mov,aac","-select_streams","a:0",
                "-show_entries","stream=codec_type,duration:format=duration","-of","json",path.toString()),10);
            metadata=new JsonMapper().readTree(bytes);
        } catch(InterruptedException error) {Thread.currentThread().interrupt();throw FileFailure.unavailable();}
        catch(Exception error) {throw new FileFailure("INVALID_REQUEST",400,"참조 음성의 길이를 확인할 수 없습니다. 정상적인 음성 파일을 선택해 주세요.");}
        validate(metadata);
    }
    static void validate(JsonNode metadata) {
        if(metadata==null)throw new FileFailure("INVALID_REQUEST",400,"참조 음성의 길이를 확인할 수 없습니다.");
        var streams=metadata.path("streams");
        if(!streams.isArray()||streams.size()!=1||!streams.get(0).path("codec_type").asString().equals("audio"))
            throw new FileFailure("INVALID_REQUEST",400,"참조 파일에 음성 트랙이 없습니다.");
        String value=streams.get(0).path("duration").asString();
        if(value.isBlank()||value.equals("N/A"))value=metadata.path("format").path("duration").asString();
        double seconds;
        try {seconds=Double.parseDouble(value);}catch(NumberFormatException error){seconds=Double.NaN;}
        if(!Double.isFinite(seconds)||seconds<=0)throw new FileFailure("INVALID_REQUEST",400,"참조 음성의 길이를 확인할 수 없습니다.");
        if(seconds<=3)throw new FileFailure("INVALID_REQUEST",400,"3초 이하의 참조 음성은 등록할 수 없습니다. 3초를 초과하는 음성을 선택해 주세요.");
        if(seconds>30)throw new FileFailure("INVALID_REQUEST",400,"참조 음성은 30초 이하여야 합니다.");
    }
}
