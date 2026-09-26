package kr.shnea.platform.file;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FileSharesWebTest {
    @Test void videoRedirectStaysRelativeBehindTheHttpsGateway() throws Exception {
        var views=mock(FileViews.class);UUID id=UUID.randomUUID();String token="a".repeat(43);
        when(views.authorize(id,token,null)).thenReturn(mock(FilesService.Row.class));
        var links=mock(FileViews.Links.class);when(links.video()).thenReturn(mock(FileVideos.Status.class));when(views.links(id,token,null)).thenReturn(links);
        var mvc=MockMvcBuilders.standaloneSetup(new FileViewsController(views,mock(FilesService.class),mock(FileStore.class),mock(FileAccess.class))).build();
        mvc.perform(get("/api/v1/files/"+id+"/view").param("token",token).header("Host","platform.example"))
            .andExpect(status().isFound()).andExpect(header().string("Location","/file-viewer.html?id="+id+"&token="+token));
    }
    @Test void landingUsesConfiguredOriginAndGenericOgAndNoFileMetadata() throws Exception {
        var shares=mock(FileShares.class);UUID id=UUID.randomUUID();
        var mvc=MockMvcBuilders.standaloneSetup(new FileSharesController(shares,mock(FileAccess.class),"https://platform.example/auth")).setControllerAdvice(new FileErrors()).build();
        String base="/api/v1/files/shares/"+id;
        String page=mvc.perform(get(base+"/view").header("Host","attacker.example").header("X-Forwarded-Host","attacker.example"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(header().string("Referrer-Policy","no-referrer"))
            .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(page).contains("property=\"og:title\"","https://platform.example"+base+"/view","type=\"password\"").doesNotContain("attacker.example","{{","/content/","?token=");
        doThrow(FileShares.unavailable()).when(shares).available(id);
        page=mvc.perform(get(base+"/view")).andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(page).contains("새 공유 링크").doesNotContain("<form");
    }
    @Test void formErrorsAreEscapedNeverEchoPasswordsAndRateLimitsIncludeRetryHeader() throws Exception {
        var shares=mock(FileShares.class);UUID id=UUID.randomUUID();String password="private-password";
        var mvc=MockMvcBuilders.standaloneSetup(new FileSharesController(shares,mock(FileAccess.class),"https://platform.example/auth")).setControllerAdvice(new FileErrors()).build();
        when(shares.unlock(id,password)).thenThrow(new FileFailure("FILE_SHARE_PASSWORD_INVALID",401,"<script>오류</script>"));
        String base="/api/v1/files/shares/"+id;
        String page=mvc.perform(post(base+"/open").contentType("application/x-www-form-urlencoded").param("password",password)).andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(page).contains("&lt;script&gt;").doesNotContain("<script>",password);
        doThrow(new FileFailure("FILE_SHARE_RATE_LIMITED",429,"잠시 후 다시 시도해 주세요.")).when(shares).unlock(id,password);
        mvc.perform(post(base+"/open").contentType("application/x-www-form-urlencoded").param("password",password)).andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After","900"));
        mvc.perform(post(base+"/access").contentType("application/json").content("{\"password\":\""+password+"\"}")).andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After","900"));
    }
}
