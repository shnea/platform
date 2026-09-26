package kr.shnea.platform.file;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class FileContractTest {
    @Test void shippedContractCoversOperationsAndResponseFields() throws Exception {
        var spec = new JsonMapper().readTree(getClass().getResourceAsStream("/openapi.json"));
        Set<String> actual = new HashSet<>(), documented = new HashSet<>();
        for (var controller : List.of(FilesController.class, FileContract.class, AdminFilesController.class, TicketDownloadController.class,RetentionController.class,FileViewsController.class,FileVideosController.class))
            for (var method : controller.getDeclaredMethods()) {
                var mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping != null) for (String path : mapping.path()) for (var verb : mapping.method()) actual.add(verb.name().toLowerCase()+" "+path);
            }
        var paths = spec.path("paths");
        for (String path : paths.propertyNames()) for (String method : paths.path(path).propertyNames()) documented.add(method+" "+path);
        assertThat(documented).isEqualTo(actual).hasSize(39);
        for (var model : Map.of("Upload",FilesService.Upload.class, "UploadCreate",FilesService.Create.class,
                "FileInfo",FilesService.FileInfo.class,"Visibility",FilesController.Visibility.class,
                "FileViewLinks",FileViews.Links.class,"VideoStatus",FileVideos.Status.class,"VideoVariant",FileVideos.Variant.class).entrySet()) {
            assertThat(spec.path("components").path("schemas").path(model.getKey()).path("properties").propertyNames())
                .containsExactlyInAnyOrderElementsOf(Arrays.stream(model.getValue().getRecordComponents()).map(c -> c.getName()).toList());
        }
    }
}
