package kr.shnea.platform.file;

import java.util.*;
import tools.jackson.databind.json.JsonMapper;

record VideoOptions(Double seconds, Subtitles subtitles) {
    static final Set<String> LANGUAGES=Set.of("auto","ko","en","ja","zh","yue");
    static final Set<String> ARTIFACTS=Set.of("subtitles.srt","subtitles.vtt","transcript.json","transcript.txt");
    record Subtitles(String mode,String language,Boolean useItn) {}
    static VideoOptions normalize(VideoOptions value) {
        if(value==null)return null;
        double seconds=value.seconds()==null?0:value.seconds();
        if(!Double.isFinite(seconds)||seconds<0||seconds>=3600)throw FileFailure.invalid();
        Subtitles subtitles=value.subtitles();
        if(subtitles!=null) {
            String language=subtitles.language()==null?"auto":subtitles.language();
            if(!Set.of("sidecar","burned").contains(Objects.toString(subtitles.mode(),""))||!LANGUAGES.contains(language))throw FileFailure.invalid();
            subtitles=new Subtitles(subtitles.mode(),language,subtitles.useItn()==null||subtitles.useItn());
        }
        return seconds==0&&subtitles==null?null:new VideoOptions(seconds,subtitles);
    }
    Map<String,Object> upstream() {
        var result=new LinkedHashMap<String,Object>();result.put("seconds",seconds);
        if(subtitles!=null)result.put("subtitles",Map.of("mode",subtitles.mode(),"language",subtitles.language(),"use_itn",subtitles.useItn()));
        return result;
    }
    static String encode(VideoOptions value) {return new JsonMapper().writeValueAsString(value==null?Map.of():value.upstream());}
    static VideoOptions decode(String value) {
        var node=new JsonMapper().readTree(value);
        if(node.isEmpty())return null;
        var subtitles=node.path("subtitles");
        return normalize(new VideoOptions(node.path("seconds").asDouble(0),subtitles.isObject()?new Subtitles(subtitles.path("mode").asString(),subtitles.path("language").asString(),subtitles.path("use_itn").asBoolean()):null));
    }
}
