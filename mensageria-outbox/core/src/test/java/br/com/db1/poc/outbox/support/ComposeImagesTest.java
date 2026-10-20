//package br.com.db1.poc.outbox.support;
//
//import static org.junit.jupiter.api.Assertions.assertEquals;
//import static org.junit.jupiter.api.Assertions.assertFalse;
//
//import java.io.IOException;
//import java.nio.charset.StandardCharsets;
//import java.nio.file.Files;
//import java.nio.file.Path;
//import java.nio.file.Paths;
//import java.util.ArrayList;
//import java.util.Arrays;
//import java.util.List;
//import java.util.regex.Matcher;
//import java.util.regex.Pattern;
//import org.junit.jupiter.api.Test;
//
///** TC-60: as imagens do {@code compose.yaml} têm as mesmas tags fixas que {@link Images}, e nenhuma é {@code latest}. */
//class ComposeImagesTest {
//
//    private static final Pattern IMAGE = Pattern.compile("^\\s*image:\\s*(\\S+)\\s*$", Pattern.MULTILINE);
//
//    @Test
//    void imagensDoComposeCoincidemComAsDosTestes() throws IOException {
//        Path compose = Paths.get(System.getProperty("basedir", "."), "..", "compose.yaml").normalize();
//        String content = new String(Files.readAllBytes(compose), StandardCharsets.UTF_8);
//        Matcher matcher = IMAGE.matcher(content);
//        List<String> images = new ArrayList<>();
//        while (matcher.find()) {
//            images.add(matcher.group(1));
//        }
//
//        assertEquals(Arrays.asList(Images.SQL_SERVER, Images.RABBITMQ, Images.TOXIPROXY), images);
//        assertFalse(images.stream().anyMatch(image -> image.endsWith(":latest") || !image.contains(":")),
//                "imagem sem tag fixa: " + images);
//    }
//}
