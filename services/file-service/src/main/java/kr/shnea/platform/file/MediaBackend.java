package kr.shnea.platform.file;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class MediaBackend {
    private final String backend;
    MediaBackend(@Value("${platform.files.processing-backend:noedaeri}") String backend) {
        if (!backend.equals("noedaeri") && !backend.equals("local") && !backend.equals("none"))
            throw new IllegalArgumentException("Invalid media backend");
        this.backend = backend;
    }
    boolean remote() { return backend.equals("noedaeri"); }
    boolean disabled() { return backend.equals("none"); }
}
