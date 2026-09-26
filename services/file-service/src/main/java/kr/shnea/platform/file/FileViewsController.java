package kr.shnea.platform.file;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import jakarta.servlet.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.HtmlUtils;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

@RestController
class FileViewsController {
    private final FileViews views;private final FilesService files;private final FileStore store;private final FileAccess access;
    FileViewsController(FileViews views,FilesService files,FileStore store,FileAccess access){this.views=views;this.files=files;this.store=store;this.access=access;}
    @PostMapping("/api/v1/files/admin/environments/{environmentId}/{id}/views")
    Object admin(@PathVariable UUID environmentId,@PathVariable UUID id,@AuthenticationPrincipal Jwt user) {
        return views.manage(id,access.administrator(environmentId,user.getSubject()),user.getExpiresAt());
    }
    @PostMapping("/api/v1/files/admin/environments/{environmentId}/{id}/views/retry")
    Object retry(@PathVariable UUID environmentId,@PathVariable UUID id,@AuthenticationPrincipal Jwt user) {
        return views.retry(id,access.administrator(environmentId,user.getSubject()));
    }
    @PostMapping("/api/v1/files/{id}/view-ticket")
    Object ticket(@PathVariable UUID id,@RequestHeader(value="X-Platform-Key",required=false) String key) {
        return views.manage(id,access.require(key,"files:read"),null);
    }
    @GetMapping("/api/v1/files/{id}/views")
    Object info(@PathVariable UUID id,@RequestParam(required=false) String token,@RequestHeader(value="X-Platform-Key",required=false) String key) {
        views.authorize(id,token,key);return views.links(id,token,null);
    }
    @GetMapping("/api/v1/files/{id}/content/{variant}")
    void content(@PathVariable UUID id,@PathVariable String variant,@RequestParam(required=false) String token,
                 @RequestHeader(value="X-Platform-Key",required=false) String key,HttpServletRequest request,HttpServletResponse response) throws IOException {
        var row=views.authorize(id,token,key);var view=views.view(id);
        switch(variant) {
            case "download" -> FileDelivery.send(files,store,row,request,response);
            case "original" -> FileDelivery.send(files,store.path(id),row,view.state().equals("READY")?view.mediaType():"application/octet-stream",view.state().equals("READY"),request,response);
            case "thumbnail" -> {if(!view.thumbnail())throw FileFailure.missing();FileDelivery.send(files,store.thumbnail(id),row,"image/jpeg",true,request,response);}
            case "preview" -> {
                if(!view.state().equals("READY"))throw new FileFailure("FILE_PREVIEW_UNAVAILABLE",409,"미리보기가 아직 준비되지 않았거나 지원하지 않는 파일입니다.");
                if(Set.of("TEXT","MARKDOWN").contains(view.kind())) {
                    var lease=files.beginDownload(row);boolean success=false;
                    try {
                        String text=views.text(id);String body=view.kind().equals("MARKDOWN")?markdown(text):"<pre>"+escape(text)+"</pre>";
                        html(response,row.name(),body,true,request);success=!request.getMethod().equals("HEAD");
                    }finally{files.finishDownload(lease,row,success);}
                }else FileDelivery.send(files,store.path(id),row,view.mediaType(),true,request,response);
            }
            default -> throw FileFailure.missing();
        }
    }
    @GetMapping("/api/v1/files/{id}/view")
    void viewer(@PathVariable UUID id,@RequestParam(required=false) String token,HttpServletRequest request,HttpServletResponse response) throws IOException {
        var row=views.authorize(id,token,null);var links=views.links(id,token,null);
        // Keep redirects relative: the servlet sees internal HTTP behind the HTTPS gateway.
        if(links.video()!=null||links.kind().equals("IMAGE")){response.setStatus(302);response.setHeader("Location","/file-viewer.html?id="+id+(token==null?"":"&token="+token));return;}
        String source=escape(links.previewUrl()==null?"":links.previewUrl());String content;
        if(links.previewUrl()==null)content="<p>미리보기가 준비되지 않았거나 지원하지 않는 형식입니다. 원본을 다운로드해 확인해 주세요.</p>";
        else content=switch(links.kind()) {
            case "IMAGE" -> "<img alt=\""+escape(row.name())+"\" src=\""+source+"\">";
            case "VIDEO" -> "<video controls playsinline preload=\"metadata\" src=\""+source+"\"></video><p>재생되지 않으면 원본을 내려받아 확인해 주세요. 브라우저마다 지원 코덱이 다를 수 있습니다.</p>";
            case "AUDIO" -> "<audio controls preload=\"metadata\" src=\""+source+"\"></audio>";
            default -> "<iframe title=\"문서 미리보기\" src=\""+source+"\"></iframe>";
        };
        html(response,row.name(),"<h1>"+escape(row.name())+"</h1><nav><a href=\""+escape(links.originalUrl())+"\">원본 보기</a> · <a href=\""+escape(links.downloadUrl())+"\">다운로드</a></nav>"+content,false,request);
    }
    static String markdown(String text){return HtmlRenderer.builder().escapeHtml(true).sanitizeUrls(true).build().render(Parser.builder().build().parse(text));}
    private static String escape(String text){return HtmlUtils.htmlEscape(text);}
    private static void html(HttpServletResponse response,String title,String body,boolean document,HttpServletRequest request) throws IOException {
        response.setContentType("text/html; charset=UTF-8");response.setHeader("X-Frame-Options","SAMEORIGIN");
        response.setHeader("Content-Security-Policy",(document?"sandbox; ":"")+"default-src 'none'; style-src 'unsafe-inline'; "+(document?"":"img-src 'self'; media-src 'self'; frame-src 'self'; ")+"frame-ancestors 'self'; base-uri 'none'; form-action 'none'");
        byte[] page=("<!doctype html><html lang=\"ko\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>"+escape(title)+"</title><style>html{color-scheme:light dark}body{font:16px/1.65 system-ui,sans-serif;margin:24px;overflow-wrap:anywhere}h1{font-size:1.4rem}pre{white-space:pre-wrap}img,video,audio{display:block;max-width:100%;max-height:75vh;margin:24px auto}iframe{width:100%;height:75vh;border:0;margin-top:24px}nav{margin-bottom:24px}</style><body>"+body+"</body></html>").getBytes(StandardCharsets.UTF_8);
        response.setContentLength(page.length);if(!request.getMethod().equals("HEAD")){response.getOutputStream().write(page);response.flushBuffer();}
    }
}
