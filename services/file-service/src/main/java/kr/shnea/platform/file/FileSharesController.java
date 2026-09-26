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
class FileSharesController {
    record Password(String password) {}
    private final FileShares shares;private final FileAccess access;private final String origin,template;
    @GetMapping(value="/api/v1/files/shares/preview.png",produces="image/png")
    org.springframework.core.io.Resource preview(){return new org.springframework.core.io.ClassPathResource("file-share-preview.png");}
    FileSharesController(FileShares shares,FileAccess access,@Value("${KEYCLOAK_PUBLIC_URL}") String publicUrl) throws IOException {
        this.shares=shares;this.access=access;URI uri=URI.create(publicUrl);
        if(!java.util.Set.of("http","https").contains(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null)throw new IllegalArgumentException("Invalid public URL");
        origin=uri.getScheme()+"://"+uri.getRawAuthority();
        try(var in=getClass().getResourceAsStream("/file-share.html")){template=new String(in.readAllBytes(),StandardCharsets.UTF_8);}
    }
    @GetMapping("/api/v1/files/admin/environments/{environmentId}/{id}/shares")
    Object adminList(@PathVariable UUID environmentId,@PathVariable UUID id,@AuthenticationPrincipal Jwt user){return shares.list(id,access.administrator(environmentId,user.getSubject()));}
    @PostMapping("/api/v1/files/admin/environments/{environmentId}/{id}/shares")
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    Object adminCreate(@PathVariable UUID environmentId,@PathVariable UUID id,@AuthenticationPrincipal Jwt user,@RequestBody FileShares.Create body){return shares.create(id,access.administrator(environmentId,user.getSubject()),body);}
    @DeleteMapping("/api/v1/files/admin/environments/{environmentId}/{id}/shares/{shareId}")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    void adminRevoke(@PathVariable UUID environmentId,@PathVariable UUID id,@PathVariable UUID shareId,@AuthenticationPrincipal Jwt user){shares.revoke(id,shareId,access.administrator(environmentId,user.getSubject()));}
    @GetMapping("/api/v1/files/{id}/shares")
    Object list(@PathVariable UUID id,@RequestHeader(value="X-Platform-Key",required=false) String key){return shares.list(id,access.require(key,"files:share"));}
    @PostMapping("/api/v1/files/{id}/shares")
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    Object create(@PathVariable UUID id,@RequestHeader(value="X-Platform-Key",required=false) String key,@RequestBody FileShares.Create body){return shares.create(id,access.require(key,"files:share"),body);}
    @DeleteMapping("/api/v1/files/{id}/shares/{shareId}")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    void revoke(@PathVariable UUID id,@PathVariable UUID shareId,@RequestHeader(value="X-Platform-Key",required=false) String key){shares.revoke(id,shareId,access.require(key,"files:share"));}
    @PostMapping(value="/api/v1/files/shares/{shareId}/access",consumes="application/json")
    Object unlock(@PathVariable UUID shareId,@RequestBody Password body){return shares.unlock(shareId,body.password());}
    @GetMapping("/api/v1/files/shares/{shareId}/view")
    void page(@PathVariable UUID shareId,HttpServletRequest request,HttpServletResponse response) throws IOException {
        try{shares.available(shareId);render(shareId,null,request,response);}
        catch(FileFailure failure){render(shareId,failure,request,response);}
    }
    @PostMapping(value="/api/v1/files/shares/{shareId}/open",consumes="application/x-www-form-urlencoded")
    void open(@PathVariable UUID shareId,@RequestParam(defaultValue="") String password,HttpServletRequest request,HttpServletResponse response) throws IOException {
        try{var links=shares.unlock(shareId,password);response.setStatus(303);response.setHeader("Location",links.viewerUrl());response.setHeader("Cache-Control","no-store");response.setHeader("Referrer-Policy","no-referrer");}
        catch(FileFailure failure){render(shareId,failure,request,response);}
    }
    private void render(UUID id,FileFailure error,HttpServletRequest request,HttpServletResponse response) throws IOException {
        response.setStatus(error==null?200:error.status);response.setContentType("text/html; charset=UTF-8");
        response.setHeader("Cache-Control","no-store");response.setHeader("Referrer-Policy","no-referrer");response.setHeader("X-Content-Type-Options","nosniff");
        response.setHeader("X-Robots-Tag","noindex, nofollow, noarchive");response.setHeader("X-Frame-Options","DENY");
        response.setHeader("Content-Security-Policy","default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'");
        if(error!=null&&error.status==429)response.setHeader("Retry-After","900");
        String base="/api/v1/files/shares/"+id;
        String form=error!=null&&error.status==404?"<p>파일을 보낸 사람에게 새 공유 링크를 요청해 주세요.</p>":"""
            <form method="post" action="%s/open">
              <label for="password">공유 비밀번호</label>
              <input id="password" name="password" type="password" required minlength="8" maxlength="64" autocomplete="current-password" aria-describedby="password-help">
              <p id="password-help" class="hint">전달받은 비밀번호 8~64자를 입력해 주세요.</p>
              <button type="submit">파일 열기</button>
            </form>
            <p class="hint foot">파일 보기와 다운로드가 허용됩니다. 인증은 최대 2시간 유지되며, 링크가 만료되거나 철회되면 다시 열 수 없습니다.</p>
            """.formatted(base);
        String body=template.replace("{{origin}}",HtmlUtils.htmlEscape(origin)).replace("{{canonical}}",HtmlUtils.htmlEscape(origin+base+"/view"))
            .replace("{{message}}",error==null?"":"<p role=\"alert\" class=\"error\">"+HtmlUtils.htmlEscape(error.getMessage())+"</p>").replace("{{form}}",form);
        byte[] bytes=body.getBytes(StandardCharsets.UTF_8);response.setContentLength(bytes.length);
        if(!request.getMethod().equals("HEAD"))response.getOutputStream().write(bytes);
    }
}
