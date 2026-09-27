package kr.shnea.platform.file;

import java.nio.file.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ImagePreviewWebTest {
    @TempDir Path directory;
    @Test void previewUsesDerivativeOrOriginalFallbackButNeverBypassesInspectionOrAccess() throws Exception {
        UUID id=UUID.randomUUID();var views=mock(FileViews.class);var files=mock(FilesService.class);var store=mock(FileStore.class);
        var row=mock(FilesService.Row.class);when(row.id()).thenReturn(id);when(row.name()).thenReturn("photo.png");
        Path original=Files.writeString(directory.resolve("original.png"),"original-bytes");
        Path preview=Files.writeString(directory.resolve("preview.webp"),"preview-bytes");
        when(store.path(id)).thenReturn(original);when(store.preview(id)).thenReturn(preview);
        when(views.authorize(id,"allowed",null)).thenReturn(row);
        when(views.view(id)).thenReturn(new FileViews.View("READY","IMAGE","image/png",true,null));
        var mvc=MockMvcBuilders.standaloneSetup(new FileViewsController(views,files,store,mock(FileAccess.class))).setControllerAdvice(new FileErrors()).build();
        String base="/api/v1/files/"+id+"/content/";
        mvc.perform(get(base+"preview").param("token","allowed")).andExpect(status().isOk()).andExpect(content().contentType("image/webp")).andExpect(content().string("preview-bytes"));
        mvc.perform(get(base+"original").param("token","allowed")).andExpect(content().contentType("image/png")).andExpect(content().string("original-bytes"));
        mvc.perform(get(base+"preview").param("token","allowed").header("Range","bytes=0-6")).andExpect(status().isPartialContent()).andExpect(content().string("preview"));
        when(views.view(id)).thenReturn(new FileViews.View("QUEUED","IMAGE","image/png",true,null));
        Files.delete(preview);
        mvc.perform(get(base+"preview").param("token","allowed")).andExpect(status().isOk()).andExpect(content().contentType("image/png")).andExpect(content().string("original-bytes"));
        when(views.view(id)).thenReturn(new FileViews.View("QUEUED","OTHER","application/octet-stream",false,null));
        mvc.perform(get(base+"preview").param("token","allowed")).andExpect(status().isConflict());
        when(views.authorize(id,null,null)).thenThrow(FileFailure.missing());
        mvc.perform(get(base+"preview")).andExpect(status().isNotFound());
    }
}
