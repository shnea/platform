package kr.shnea.platform.file;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import jakarta.servlet.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
class FileVideosController {
    private final FileVideos videos;private final FileViews views;private final FilesService files;private final FileAccess access;
    FileVideosController(FileVideos videos,FileViews views,FilesService files,FileAccess access){this.videos=videos;this.views=views;this.files=files;this.access=access;}
    @PostMapping("/api/v1/files/admin/environments/{environmentId}/{id}/video/retry")
    Object retry(@PathVariable UUID environmentId,@PathVariable UUID id,@AuthenticationPrincipal Jwt user){return videos.retry(id,access.administrator(environmentId,user.getSubject()));}
    @GetMapping("/api/v1/files/{id}/hls/{asset}")
    void content(@PathVariable UUID id,@PathVariable String asset,@RequestParam(required=false) String token,
                 @RequestHeader(value="X-Platform-Key",required=false) String key,HttpServletRequest request,HttpServletResponse response) throws IOException {
        var row=views.authorize(id,token,key,true);
        if(asset.endsWith(".m3u8")) {
            byte[] body=videos.playlist(id,asset,token).getBytes(StandardCharsets.UTF_8);
            // A manifest is metadata, not successful content use. Segments refresh last-used through FileDelivery.
            response.setContentType("application/vnd.apple.mpegurl");response.setContentLength(body.length);
            if(!request.getMethod().equals("HEAD"))response.getOutputStream().write(body);
        }else FileDelivery.send(files,videos.asset(id,asset),row,"video/mp2t",true,request,response);
    }
}
