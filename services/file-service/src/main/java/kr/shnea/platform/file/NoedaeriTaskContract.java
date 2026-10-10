package kr.shnea.platform.file;

import java.io.IOException;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

final class NoedaeriTaskContract {
    static final Set<String> KINDS=Set.of("video.subtitles","pdf.extract","ocr.recognize","stt.transcribe","tts.synthesize","video.thumbnail","tts.voice.register");
    static final Set<String> SPEAKERS=Set.of("Sohee","Vivian","Serena","Uncle_Fu","Dylan","Eric","Ryan","Aiden","Ono_Anna");
    static final Set<String> SPEECH_LANGUAGES=Set.of("Korean","English","Japanese","Chinese","German","French","Russian","Portuguese","Spanish","Italian");
    static final Set<String> IMAGE_EXTENSIONS=Set.of("png","jpg","jpeg","jfif","gif","webp","bmp","ico","tif","tiff","heic","heif","avif");
    static final Set<String> AUDIO_EXTENSIONS=Set.of("wav","mp3","flac","ogg","m4a","mp4","mov","webm","mkv","aac","aiff");
    static final Set<String> STT_LANGUAGES=Set.of("auto","ko","en","ja","zh","yue");
    static final Set<String> OCR_LANGUAGES=Set.of("auto","ko","en","ja","zh-Hans","zh-Hant");
    private static final JsonMapper JSON=new JsonMapper();
    private NoedaeriTaskContract() {}
    static String requester(FileAccess.Context context) {return "admin:"+context.credentialId();}
    static UUID uuid(JsonNode value) {
        try{return NoedaeriMedia.uuid(value);}catch(IOException error){throw FileFailure.invalid();}
    }
    static void fields(JsonNode body,String... allowed) {
        if(!body.isObject()||!Set.of(allowed).containsAll(body.propertyNames()))throw FileFailure.invalid();
    }
    static String string(JsonNode body,String key,int max,boolean required) {
        var value=body.path(key);
        if(value.isMissingNode()||value.isNull()){if(required)throw FileFailure.invalid();return "";}
        if(!value.isString()||value.asString().length()>max||required&&value.asString().isBlank())throw FileFailure.invalid();
        return value.asString();
    }
    static Map<String,Object> options(String kind,JsonNode body) {
        var options=body.isMissingNode()?JSON.createObjectNode():body;
        var result=new LinkedHashMap<String,Object>();
        switch(kind) {
            case "video.subtitles","stt.transcribe" -> {
                fields(options,"language","use_itn");
                result.put("language",choice(options,"language","auto",STT_LANGUAGES));
                result.put("use_itn",flag(options,"use_itn",true));
            }
            case "pdf.extract","ocr.recognize" -> {
                if(kind.equals("pdf.extract")){fields(options,"mode","language","language_correction");result.put("mode",choice(options,"mode","auto",Set.of("auto","ocr","text")));}
                else fields(options,"language","language_correction");
                result.put("language",choice(options,"language","auto",OCR_LANGUAGES));
                result.put("language_correction",flag(options,"language_correction",true));
            }
            case "tts.synthesize" -> {
                fields(options,"voice_id","instruct");
                var voice=options.path("voice_id");
                if(!voice.isMissingNode()&&!voice.isNull())result.put("voice_id",uuid(voice).toString());
                result.put("instruct",string(options,"instruct",300,false));
            }
            case "tts.voice.register" -> fields(options);
            case "video.thumbnail" -> {
                fields(options,"seconds");var seconds=options.path("seconds");
                if(!seconds.isMissingNode()&&(!seconds.isNumber()||!Double.isFinite(seconds.asDouble())||seconds.asDouble()<0||seconds.asDouble()>3600))throw FileFailure.invalid();
                result.put("seconds",seconds.isMissingNode()?0:seconds.asDouble());
            }
            default -> throw FileFailure.invalid();
        }
        return result;
    }
    static Map<String,Object> input(String kind,JsonNode body,FilesService.FileInfo source,FileAccess.Context context) {
        if(kind.equals("tts.voice.register")) {
            fields(body,"name","kind","speaker","reference_text");
            var result=new LinkedHashMap<String,Object>();String mode=string(body,"kind",12,true);
            result.put("name",string(body,"name",120,true));result.put("kind",mode);
            result.put("requester_id",requester(context));result.put("project",context.projectId().toString());result.put("environment",context.environmentId().toString());
            if(mode.equals("preset")) {
                String speaker=string(body,"speaker",30,true);
                if(!SPEAKERS.contains(speaker)||source!=null||body.has("reference_text"))throw FileFailure.invalid();result.put("speaker",speaker);
            }else if(mode.equals("clone")) {
                if(source==null||source.size()>64*1024*1024||body.has("speaker"))throw FileFailure.invalid();
                String name=source.originalName(),extension=name.substring(name.lastIndexOf('.')+1).toLowerCase(Locale.ROOT);
                if(!Set.of("wav","mp3","flac","ogg","m4a","aac").contains(extension))throw FileFailure.invalid();
                result.put("reference_text",string(body,"reference_text",1000,true));
            }else throw FileFailure.invalid();
            return result;
        }
        if(kind.equals("tts.synthesize")) {
            fields(body,"text","language");
            return Map.of("type","text","text",string(body,"text",4000,true),"language",choice(body,"language","Korean",SPEECH_LANGUAGES),
                "requester_id",requester(context),"project",context.projectId().toString(),"environment",context.environmentId().toString());
        }
        if(source==null||!body.isMissingNode())throw FileFailure.invalid();
        String name=source.originalName(),extension=name.substring(name.lastIndexOf('.')+1).toLowerCase(Locale.ROOT);
        boolean accepted=switch(kind) {
            case "ocr.recognize" -> IMAGE_EXTENSIONS.contains(extension);
            case "pdf.extract" -> extension.equals("pdf");
            case "video.thumbnail" -> Set.of("mp4","m4v","mov","mkv","webm").contains(extension);
            case "video.subtitles","stt.transcribe" -> AUDIO_EXTENSIONS.contains(extension);
            default -> false;
        };
        if(!accepted)throw FileFailure.invalid();
        return kind.equals("ocr.recognize")?Map.of("type","upload","extension",extension):Map.of("type","upload");
    }
    static List<String> files(String kind) {
        return switch(kind) {
            case "video.subtitles" -> List.of("transcript.json","transcript.txt","subtitles.srt","subtitles.vtt","subtitles.zip");
            case "pdf.extract" -> List.of("document.json","document.txt","document.zip");
            case "ocr.recognize" -> List.of("text.json","text.txt","text.zip");
            case "stt.transcribe" -> List.of("transcript.json","transcript.txt","transcript.zip");
            case "tts.synthesize" -> List.of("speech.wav");
            case "tts.voice.register" -> List.of("reference.wav");
            case "video.thumbnail" -> List.of("thumbnail.jpg");
            default -> throw FileFailure.invalid();
        };
    }
    static long fileLimit(String name) {return name.endsWith(".wav")?64*1024*1024:name.endsWith(".zip")?32*1024*1024:name.endsWith(".jpg")?2*1024*1024:4*1024*1024;}
    static void manifest(String kind,JsonNode result) throws IOException {
        String expected=switch(kind) {
            case "video.subtitles" -> "video_subtitles";
            case "pdf.extract" -> "pdf_extract";
            case "ocr.recognize" -> "ocr_recognize";
            case "stt.transcribe" -> "stt_transcribe";
            default -> "artifact";
        };
        if(!result.isObject()||!result.path("type").asString().equals(expected))throw new IOException("Invalid task manifest");
        if(expected.equals("artifact")) {
            String name=files(kind).getFirst(),mime=kind.equals("tts.synthesize")?"audio/wav":"image/jpeg";
            if(!result.path("name").asString().equals(name)||!result.path("media_type").asString().equals(mime))throw new IOException("Invalid single artifact");
        }else {
            var names=new HashSet<String>();
            if(!result.path("files").isArray())throw new IOException("Missing task files");
            for(var value:result.path("files"))if(!value.isString()||!names.add(value.asString()))throw new IOException("Invalid task files");
            if(!names.equals(new HashSet<>(files(kind))))throw new IOException("Incomplete task files");
        }
    }
    static JsonNode validate(String kind,String name,byte[] bytes) throws Exception {
        if(bytes.length>fileLimit(name))throw new IOException("Task artifact too large");
        if(name.endsWith(".zip")){zip(kind,bytes);return null;}
        if(name.endsWith(".jpg")){if(bytes.length<4||(bytes[0]&255)!=255||(bytes[1]&255)!=216||(bytes[2]&255)!=255)throw new IOException("Invalid JPEG");return null;}
        if(name.endsWith(".wav")){long samples=wav(bytes);if(kind.equals("tts.voice.register")&&(samples<144000||samples>1440000))throw new IOException("Invalid reference duration");return null;}
        String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        if(text.indexOf('\0')>=0)throw new IOException("Invalid text artifact");
        if(name.endsWith(".vtt")&&!text.replaceFirst("^\uFEFF","").startsWith("WEBVTT"))throw new IOException("Invalid VTT");
        if(!name.endsWith(".json"))return null;
        var result=JSON.readTree(text);
        if(!result.isObject()||!result.path("text").isString())throw new IOException("Invalid transcript");
        if(kind.equals("pdf.extract")) {
            var pages=result.path("pages");if(!pages.isArray()||pages.size()>500||result.path("page_count").asInt()!=pages.size())throw new IOException("Invalid pages");
            int expectedPage=1,totalLines=0;
            for(var page:pages){if(page.path("page").asInt()!=expectedPage++||!page.path("text").isString()||!Set.of("text","ocr").contains(page.path("method").asString()))throw new IOException("Invalid page");totalLines+=lines(page.path("lines"));}
            if(totalLines>10000)throw new IOException("Too many OCR lines");
        }else if(kind.equals("ocr.recognize")) {
            if(!result.path("coordinate_system").asString().equals("normalized_top_left"))throw new IOException("Invalid coordinate system");lines(result.path("lines"));
        }else {
            var segments=result.path("segments");double duration=result.path("duration_seconds").asDouble(-1),previous=0;
            if(!segments.isArray()||segments.size()>20000||!Double.isFinite(duration)||duration<0||duration>3600)throw new IOException("Invalid segments");
            for(var segment:segments){double start=segment.path("start").asDouble(-1),end=segment.path("end").asDouble(-1);if(!Double.isFinite(start)||!Double.isFinite(end)||start<previous||end<start||end>duration+1||!segment.path("text").isString())throw new IOException("Invalid segment");previous=start;}
        }
        return result;
    }
    private static int lines(JsonNode lines) throws IOException {
        if(!lines.isArray()||lines.size()>10000)throw new IOException("Invalid OCR lines");
        for(var line:lines) {
            if(!line.path("text").isString())throw new IOException("Invalid OCR line");
            var box=line.path("bounding_box");
            for(String key:List.of("left","top","width","height")){double value=box.path(key).asDouble(-1);if(!Double.isFinite(value)||value<0||value>1)throw new IOException("Invalid OCR box");}
            if(box.path("left").asDouble()+box.path("width").asDouble()>1.001||box.path("top").asDouble()+box.path("height").asDouble()>1.001)throw new IOException("Invalid OCR box");
        }
        return lines.size();
    }
    private static void zip(String kind,byte[] bytes) throws Exception {
        var expected=new HashSet<>(files(kind));expected.removeIf(name->name.endsWith(".zip"));var found=new HashSet<String>();
        try(var input=new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(bytes),StandardCharsets.UTF_8)) {
            java.util.zip.ZipEntry entry;
            while((entry=input.getNextEntry())!=null) {
                String name=entry.getName();if(entry.isDirectory()||!expected.contains(name)||!found.add(name))throw new IOException("Invalid ZIP entry");
                byte[] content=input.readNBytes((int)fileLimit(name)+1);if(content.length>fileLimit(name))throw new IOException("Expanded artifact too large");
                validate(kind,name,content);input.closeEntry();
            }
        }
        if(!found.equals(expected))throw new IOException("Incomplete ZIP");
    }
    private static String choice(JsonNode body,String field,String fallback,Set<String> choices) {
        String value=body.path(field).isMissingNode()?fallback:string(body,field,40,true);
        if(!choices.contains(value))throw FileFailure.invalid();return value;
    }
    private static boolean flag(JsonNode body,String field,boolean fallback) {
        var value=body.path(field);if(value.isMissingNode())return fallback;if(!value.isBoolean())throw FileFailure.invalid();return value.asBoolean();
    }
    private static long wav(byte[] bytes) throws IOException {
        if(bytes.length<44||!new String(bytes,0,4,StandardCharsets.US_ASCII).equals("RIFF")||!new String(bytes,8,4,StandardCharsets.US_ASCII).equals("WAVE"))throw new IOException("Invalid WAV");
        var data=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);boolean format=false,samples=false;long sampleBytes=0;
        for(int offset=12;offset+8<=bytes.length;) {
            String chunk=new String(bytes,offset,4,StandardCharsets.US_ASCII);long length=Integer.toUnsignedLong(data.getInt(offset+4));
            if(length>bytes.length-offset-8)throw new IOException("Invalid WAV chunk");
            if(chunk.equals("fmt ")){if(length<16||data.getShort(offset+8)!=1||data.getShort(offset+10)!=1||data.getInt(offset+12)!=24000||data.getShort(offset+22)!=16)throw new IOException("Invalid speech format");format=true;}
            if(chunk.equals("data")){if(length==0||length%2!=0)throw new IOException("Invalid speech samples");samples=true;sampleBytes+=length;}
            offset+=(int)length+8+(int)(length%2);
        }
        if(!format||!samples)throw new IOException("Missing speech data");
        return sampleBytes;
    }
}
