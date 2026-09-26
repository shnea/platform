package kr.shnea.platform.file;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FilePublicSharesWebTest {
    @Test void publicOgEscapesMetadataUsesTrustedOriginAndNeverAcceptsPrivateTokens()throws Exception{
        UUID id=UUID.randomUUID();var shares=mock(FilePublicShares.class);var views=mock(FileViews.class);
        var row=mock(FilesService.Row.class);when(row.name()).thenReturn("파일.txt");when(views.authorize(id,null,null)).thenReturn(row);
        var links=mock(FileViews.Links.class);when(views.links(id,null,null)).thenReturn(links);when(links.viewerUrl()).thenReturn("/viewer");when(links.downloadUrl()).thenReturn("/download");when(links.thumbnailUrl()).thenReturn("/thumbnail");
        when(shares.settings(id)).thenReturn(new FilePublicShares.Settings("<script> {{image}}","\"설명\"",true,1L));
        var mvc=MockMvcBuilders.standaloneSetup(new FilePublicSharesController(shares,views,mock(FileAccess.class),"https://platform.example/auth")).setControllerAdvice(new FileErrors()).build();
        String path="/api/v1/files/"+id+"/share";
        String body=mvc.perform(get(path).header("Host","attacker.example")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(body).contains("&lt;script&gt; {{image}}","&quot;설명&quot;","https://platform.example/thumbnail",path).doesNotContain("<script>","attacker.example");
        when(shares.settings(id)).thenReturn(new FilePublicShares.Settings("","",false,1L));
        body=mvc.perform(get(path)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(body).contains("파일.txt","https://platform.example/api/v1/files/share-preview.png").doesNotContain("/thumbnail");
        mvc.perform(head(path)).andExpect(status().isOk()).andExpect(content().string(""));
        when(views.authorize(id,null,null)).thenThrow(FileFailure.missing());
        body=mvc.perform(get(path).param("token","secret").header("X-Platform-Key","secret")).andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(body).contains("파일을 열 수 없습니다").doesNotContain("파일.txt","<nav","/thumbnail");
    }
}
