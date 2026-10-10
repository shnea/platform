package kr.shnea.platform.file;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
class NoedaeriClient {
    static final int JSON_LIMIT=128*1024;
    final String webhookSecret;
    final long videoInputLimit;
    private final URI base;
    private final String key;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final JsonMapper json=new JsonMapper();
    @Autowired
    NoedaeriClient(@Value("${platform.noedaeri.url:}") String url,
                   @Value("${platform.noedaeri.api-key:}") String key,
                   @Value("${platform.noedaeri.webhook-secret:}") String secret,
                   @Value("${platform.noedaeri.video-input-limit:536870912}") long limit) {
        this(url.isBlank()?null:URI.create(url),key,secret,limit);
        if(base!=null&&!base.getScheme().equals("https"))throw new IllegalArgumentException("Noedaeri requires HTTPS");
    }
    // HTTP loopback is used only by isolated protocol tests; production uses the HTTPS constructor above.
    NoedaeriClient(URI base,String key,String secret,long limit) {
        if(base!=null&&(base.getHost()==null||base.getUserInfo()!=null||base.getQuery()!=null||base.getFragment()!=null
            ||!Set.of("https","http").contains(base.getScheme())||!Set.of("","/").contains(base.getPath())))
            throw new IllegalArgumentException("Invalid Noedaeri origin");
        if(limit<1||limit>FilesService.MAX_FILE)throw new IllegalArgumentException("Invalid media input limit");
        this.base=base;this.key=key;this.webhookSecret=secret;this.videoInputLimit=limit;
    }
    boolean configured(){return base!=null&&!key.isBlank()&&!webhookSecret.isBlank();}
    JsonNode create(UUID request,String kind,String extension) throws Exception {
        return create(request,kind,extension,Map.of());
    }
    JsonNode create(UUID request,String kind,String extension,Map<String,Object> options) throws Exception {
        var input=new LinkedHashMap<String,Object>();input.put("type","upload");
        if(kind.equals("image.package"))input.put("extension",extension);
        return json("POST","/api/v1/jobs",Map.of("kind",kind,"title","파일 파생물 생성","idempotency_key",request.toString(),"input",input,"options",options));
    }
    JsonNode status(UUID id) throws Exception {return json("GET",path(id),null);}
    void upload(UUID id,Path original) throws Exception {
        request("PUT",path(id)+"/input",HttpRequest.BodyPublishers.ofFile(original),"application/octet-stream",null,JSON_LIMIT);
    }
    void cancel(UUID id) throws Exception {json("POST",path(id)+"/cancel",Map.of());}
    void receipt(UUID id,UUID event) throws Exception {
        if(!json("POST",path(id)+"/receipt",Map.of("event_id",event.toString())).path("accepted").asBoolean())throw new IOException("Receipt rejected");
    }
    void download(UUID id,String name,Path output,long limit) throws Exception {
        if(!VideoOptions.ARTIFACTS.contains(name)&&!name.matches("thumbnail\\.jpg|preview\\.webp|master\\.m3u8|"+FileVideos.HLS_CHILD_PATTERN))throw new IOException("Invalid artifact name");
        request("GET",path(id)+"/files/"+name,HttpRequest.BodyPublishers.noBody(),null,output,limit);
    }
    private JsonNode json(String method,String path,Object body) throws Exception {
        byte[] bytes=body==null?null:json.writeValueAsBytes(body);
        byte[] result=request(method,path,bytes==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(bytes),
            body==null?null:"application/json",null,JSON_LIMIT);
        return json.readTree(result);
    }
    private byte[] request(String method,String path,HttpRequest.BodyPublisher body,String type,Path output,long limit) throws Exception {
        if(!configured())throw new IOException("Noedaeri is not configured");
        var builder=HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(120)).header("X-Noedaeri-API-Key",key).method(method,body);
        if(type!=null)builder.header("Content-Type",type);
        var stream=new AtomicReference<InputStream>();var executor=Executors.newVirtualThreadPerTaskExecutor();
        Future<byte[]> pending=executor.submit(()->{
            var response=http.send(builder.build(),HttpResponse.BodyHandlers.ofInputStream());
            try(InputStream in=response.body()) {
                stream.set(in);
                if(response.statusCode()<200||response.statusCode()>=300) {
                    String code="";
                    try {
                        String detail=json.readTree(in.readNBytes(JSON_LIMIT+1)).path("detail").asString();
                        if(Set.of("platform_delivery_not_configured","platform_key_required","upload_too_large","storage_capacity_exceeded","result_unavailable","result_missing","stt_not_configured","subtitle_renderer_unavailable").contains(detail))code=detail;
                    }catch(Exception ignored){}
                    throw new RemoteFailure(response.statusCode(),code);
                }
                if(response.headers().firstValueAsLong("Content-Length").orElse(0)>limit)throw new IOException("Artifact limit exceeded");
                var bytes=new ByteArrayOutputStream();
                try(OutputStream out=output==null?bytes:Files.newOutputStream(output,StandardOpenOption.CREATE_NEW)) {
                    long total=0;byte[] buffer=new byte[64*1024];int count;
                    while((count=in.read(buffer))!=-1){total+=count;if(total>limit)throw new IOException("Artifact limit exceeded");out.write(buffer,0,count);}
                }
                if(output!=null)try(var file=new RandomAccessFile(output.toFile(),"rw")){file.getFD().sync();}
                return bytes.toByteArray();
            }
        });
        try{return pending.get(125,TimeUnit.SECONDS);}
        catch(ExecutionException e){if(e.getCause() instanceof Exception cause)throw cause;throw new IOException("Remote request failed");}
        finally {
            InputStream in=stream.get();if(in!=null)try{in.close();}catch(IOException ignored){}
            pending.cancel(true);executor.shutdownNow();
        }
    }
    private static String path(UUID id){return "/api/v1/jobs/"+id;}
    static final class RemoteFailure extends IOException {
        final int status;
        final String code;
        RemoteFailure(int status){this(status,"");}
        RemoteFailure(int status,String code){super("Remote media HTTP "+status);this.status=status;this.code=code;}
    }
}
