package kr.shnea.platform.file;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.HtmlUtils;

@RestController
class FilePublicSharesController {
    private final FilePublicShares shares;private final FileViews views;private final FileAccess access;private final String origin,template;
    FilePublicSharesController(FilePublicShares shares,FileViews views,FileAccess access,@Value("${KEYCLOAK_PUBLIC_URL}")String publicUrl)throws IOException{
        this.shares=shares;this.views=views;this.access=access;URI uri=URI.create(publicUrl);
        if(!java.util.Set.of("http","https").contains(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null)throw new IllegalArgumentException("Invalid public URL");
        origin=uri.getScheme()+"://"+uri.getRawAuthority();
        try(var in=getClass().getResourceAsStream("/file-public-share.html")){template=new String(in.readAllBytes(),StandardCharsets.UTF_8);}
    }
    @GetMapping("/api/v1/files/admin/environments/{environmentId}/{id}/public-share")
    Object adminGet(@PathVariable UUID environmentId,@PathVariable UUID id,@AuthenticationPrincipal Jwt user){return shares.get(id,access.administrator(environmentId,user.getSubject()));}
    @PutMapping("/api/v1/files/admin/environments/{environmentId}/{id}/public-share")
    Object adminSave(@PathVariable UUID environmentId,@PathVariable UUID id,@AuthenticationPrincipal Jwt user,@RequestBody FilePublicShares.Settings input){return shares.save(id,access.administrator(environmentId,user.getSubject()),input);}
    @GetMapping("/api/v1/files/{id}/public-share")
    Object get(@PathVariable UUID id,@RequestHeader(value="X-Platform-Key",required=false)String key){return shares.get(id,access.require(key,"files:read"));}
    @PutMapping("/api/v1/files/{id}/public-share")
    Object save(@PathVariable UUID id,@RequestHeader(value="X-Platform-Key",required=false)String key,@RequestBody FilePublicShares.Settings input){return shares.save(id,access.require(key,"files:write"),input);}
    @GetMapping(value="/api/v1/files/share-preview.png",produces="image/png")
    org.springframework.core.io.Resource preview(){return new org.springframework.core.io.ClassPathResource("file-public-share.png");}
    @GetMapping("/api/v1/files/{id}/share")
    void page(@PathVariable UUID id,HttpServletRequest request,HttpServletResponse response)throws IOException{
        try { render(id,request,response); }
        catch(FileFailure error){
            response.setStatus(error.status);response.setContentType("text/html; charset=UTF-8");response.setHeader("Cache-Control","no-store");
            response.setHeader("X-Robots-Tag","noindex, nofollow, noarchive");response.setHeader("Referrer-Policy","no-referrer");response.setHeader("X-Frame-Options","DENY");
            response.setHeader("Content-Security-Policy","default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'");
            String body=template.replaceAll("<nav[\\s\\S]*?</nav>","").replaceAll("<p class=\"hint\">[^<]*</p>","").replace("{{title}}","파일을 열 수 없습니다").replace("{{description}}",error.status==404?"공개가 종료되었거나 사용할 수 없는 파일입니다. 파일을 보낸 사람에게 확인해 주세요.":"일시적으로 파일을 확인할 수 없습니다. 잠시 후 다시 열어 주세요.").replace("{{picture}}","").replace("{{canonical}}",escape(origin+"/api/v1/files/"+id+"/share")).replace("{{image}}",escape(origin+"/api/v1/files/share-preview.png"));
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);response.setContentLength(bytes.length);if(!request.getMethod().equals("HEAD"))response.getOutputStream().write(bytes);
        }
    }
    private void render(UUID id,HttpServletRequest request,HttpServletResponse response)throws IOException{
        // No key or token is accepted here: crawlers and visitors see the same public-only page.
        var row=views.authorize(id,null,null);var links=views.links(id,null,null);var settings=shares.settings(id);
        String title=settings.title().isBlank()?row.name():settings.title();
        String description=settings.description().isBlank()?"파일을 열어 미리보거나 다운로드할 수 있습니다.":settings.description();
        String image=origin+(settings.showThumbnail()&&links.thumbnailUrl()!=null?links.thumbnailUrl():"/api/v1/files/share-preview.png");
        String picture=settings.showThumbnail()&&links.thumbnailUrl()!=null?"<img class=\"preview\" src=\""+escape(links.thumbnailUrl())+"\" alt=\"파일 대표 이미지\">":"";
        // Replace in one pass so user text resembling a placeholder remains literal.
        var values=java.util.Map.of("title",escape(title),"description",escape(description),"canonical",escape(origin+"/api/v1/files/"+id+"/share"),"image",escape(image),"picture",picture,"viewer",escape(links.viewerUrl()),"download",escape(links.downloadUrl()));
        var matcher=java.util.regex.Pattern.compile("\\{\\{([a-z]+)\\}\\}").matcher(template);var body=new StringBuilder();
        while(matcher.find())matcher.appendReplacement(body,java.util.regex.Matcher.quoteReplacement(values.get(matcher.group(1))));matcher.appendTail(body);
        response.setContentType("text/html; charset=UTF-8");response.setHeader("Cache-Control","no-store");response.setHeader("Referrer-Policy","no-referrer");
        response.setHeader("X-Robots-Tag","noindex, nofollow, noarchive");response.setHeader("X-Content-Type-Options","nosniff");response.setHeader("X-Frame-Options","DENY");
        response.setHeader("Content-Security-Policy","default-src 'none'; img-src 'self'; style-src 'unsafe-inline'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'");
        byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);response.setContentLength(bytes.length);
        if(!request.getMethod().equals("HEAD"))response.getOutputStream().write(bytes);
    }
    private static String escape(String value){return HtmlUtils.htmlEscape(value);}
}
