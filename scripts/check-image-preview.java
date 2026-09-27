package kr.shnea.platform.file;

import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import tools.jackson.databind.json.JsonMapper;

/** Compile against the built file-service jar, run with its actual FFmpeg runtime. */
class ImagePreviewCheck {
    public static void main(String[] args) throws Exception {
        Path dir=Files.createTempDirectory("image-preview-check-");
        try {
            BufferedImage large=new BufferedImage(2400,1800,BufferedImage.TYPE_INT_RGB);
            Random random=new Random(7);
            for(int y=0;y<1800;y++)for(int x=0;x<2400;x++)large.setRGB(x,y,random.nextInt(0x1000000));
            Path png=dir.resolve("large.png");ImageIO.write(large,"png",png.toFile());
            check(png,dir.resolve("large.webp"),1600,1200,true,false);
            BufferedImage alpha=new BufferedImage(64,32,BufferedImage.TYPE_INT_ARGB);
            alpha.setRGB(32,16,0xffff0000);
            Path transparent=dir.resolve("alpha.png");ImageIO.write(alpha,"png",transparent.toFile());
            Path webp=dir.resolve("alpha.webp");check(transparent,webp,64,32,false,true);
            check(webp,dir.resolve("webp-preview.webp"),64,32,false,true);
            for(String format:List.of("jpg","gif")) {
                Path source=dir.resolve("small."+format);
                ImageIO.write(new BufferedImage(80,40,BufferedImage.TYPE_INT_RGB),format,source.toFile());
                check(source,dir.resolve(format+"-preview.webp"),80,40,false,false);
            }
            Path bad=Files.writeString(dir.resolve("broken.png"),"not an image");
            boolean rejected=false;try{FileViews.imagePreview(bad,dir.resolve("broken.webp"));}catch(Exception expected){rejected=true;}
            if(!rejected)throw new AssertionError("Corrupt image accepted");
            System.out.println("PASS: PNG/JPEG/GIF/WebP, resize, compression, alpha, no upscale, original preservation, corrupt input");
        } finally {try(var paths=Files.walk(dir)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    }
    static void check(Path input,Path output,int width,int height,boolean smaller,boolean alpha) throws Exception {
        byte[] before=Files.readAllBytes(input);FileViews.imagePreview(input,output);
        var metadata=new JsonMapper().readTree(FileViews.run(List.of("ffprobe","-v","error","-show_entries","stream=width,height,pix_fmt,codec_name","-of","json",output.toString()),10)).path("streams").get(0);
        if(metadata.path("width").asInt()!=width||metadata.path("height").asInt()!=height||!metadata.path("codec_name").asString().equals("webp"))throw new AssertionError(metadata.toString());
        if(alpha&&!metadata.path("pix_fmt").asString().contains("a"))throw new AssertionError("Transparency lost");
        if(smaller&&Files.size(output)>=before.length)throw new AssertionError("Preview is not smaller");
        if(!Arrays.equals(before,Files.readAllBytes(input)))throw new AssertionError("Original changed");
        System.out.printf("%s: %d → %d bytes, %dx%d%n",input.getFileName(),before.length,Files.size(output),width,height);
    }
}
