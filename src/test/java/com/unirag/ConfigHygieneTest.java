package com.unirag;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 密钥卫生检查：主配置中的密钥类配置项必须以外部化占位符形式出现，
 * 不得写入真实密钥（对应 spec FR-002 / SC-004）。
 */
class ConfigHygieneTest {

    private static final Path MAIN_YML = Path.of("src/main/resources/application.yml");

    @Test
    @SuppressWarnings("unchecked")
    void secretValuesMustBeExternalizedPlaceholders() throws Exception {
        assertTrue(Files.exists(MAIN_YML), "src/main/resources/application.yml must exist");

        Map<String, Object> yaml;
        try (InputStream in = Files.newInputStream(MAIN_YML)) {
            yaml = new Yaml().load(in);
        }

        Map<String, Object> models = (Map<String, Object>) getNested(yaml, "unirag", "ai", "models");
        assertNotNull(models, "unirag.ai.models must be defined");
        models.forEach((group, groupConfig) -> {
            Object apiKey = ((Map<String, Object>) groupConfig).get("api-key");
            assertPlaceholder(group + ".api-key", apiKey);
        });

        Object pgPassword = getNested(yaml, "spring", "datasource", "password");
        assertPlaceholder("spring.datasource.password", pgPassword);
    }

    @Test
    void noHardcodedSecretPatternsInConfig() throws Exception {
        String content = Files.readString(MAIN_YML);
        assertTrue(content.lines().noneMatch(line ->
                        line.matches(".*(api-key|password|apikey)\\s*:\\s*(?!\\$\\{).*")
                                && !line.trim().startsWith("#")),
                "secret-like keys must use ${...} placeholders");
    }

    private static Object getNested(Map<String, Object> root, String... path) {
        Object current = root;
        for (String key : path) {
            if (!(current instanceof Map<?, ?> map) || !map.containsKey(key)) {
                fail("missing config path: " + String.join(".", path));
            }
            current = ((Map<String, Object>) map).get(key);
        }
        return current;
    }

    private static void assertPlaceholder(String name, Object value) {
        assertNotNull(value, name + " must be defined");
        String str = assertInstanceOf(String.class, value, name + " must be a string");
        assertEquals("${" + str.substring(2, str.length() - 1) + "}", str,
                () -> name + " must be a ${...} environment placeholder, got: " + str);
        assertTrue(str.startsWith("${") && str.endsWith("}"),
                () -> name + " must be a ${...} environment placeholder, got: " + str);
    }
}
