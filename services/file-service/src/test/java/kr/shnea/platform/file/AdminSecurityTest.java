package kr.shnea.platform.file;

import jakarta.servlet.Filter;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.json.JsonMapper;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AdminSecurityTest {
    @Configuration @EnableWebSecurity @EnableWebMvc
    @Import({FileSecurity.class,FileErrors.class,AdminFilesController.class})
    static class Config {
        @Bean FileAccess access() { return mock(FileAccess.class); }
        @Bean FilesService files() { return mock(FilesService.class); }
        @Bean DownloadTickets tickets() { return mock(DownloadTickets.class); }
        @Bean JsonMapper json() { return new JsonMapper(); }
        @Bean JwtDecoder decoder() { return value -> {
            if(value.equals("invalid")) throw new BadJwtException("Invalid token");
            return Jwt.withTokenValue(value).header("alg","RS256").subject("11111111-1111-1111-1111-111111111111")
                .expiresAt(Instant.now().plusSeconds(60)).claim("realm_access",Map.of("roles",List.of(value.equals("admin")?"platform-admin":"member"))).build();
        }; }
    }
    @Test void everyAdminRouteRejectsAnonymousServerKeysAndMemberTokensBeforeBusinessLogic() throws Exception {
        try(var context=new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext()); context.register(Config.class); context.refresh();
            var mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain",Filter.class)).build();
            String root="/api/v1/files/admin/environments/"+UUID.randomUUID(), id=UUID.randomUUID().toString();
            var requests=List.of(get(root),get(root+"/uploads"),post(root+"/uploads"),get(root+"/uploads/"+id),patch(root+"/uploads/"+id),
                delete(root+"/uploads/"+id),post(root+"/uploads/"+id+"/complete"),put(root+"/"+id+"/visibility"),delete(root+"/"+id),post(root+"/"+id+"/download-ticket"));
            for(var request:requests) {
                mvc.perform(request.header("X-Platform-Key","pk_not-an-admin")).andExpect(status().isUnauthorized());
                mvc.perform(request.header("Authorization","Bearer member")).andExpect(status().isForbidden());
            }
            mvc.perform(get(root).header("Authorization","Bearer invalid")).andExpect(status().isUnauthorized());
            verifyNoInteractions(context.getBean(FileAccess.class),context.getBean(FilesService.class),context.getBean(DownloadTickets.class));
            mvc.perform(get(root).header("Authorization","Bearer admin")).andExpect(status().isOk());
            verify(context.getBean(FileAccess.class)).administrator(UUID.fromString(root.substring(root.lastIndexOf('/')+1)),"11111111-1111-1111-1111-111111111111");
        }
    }
}
