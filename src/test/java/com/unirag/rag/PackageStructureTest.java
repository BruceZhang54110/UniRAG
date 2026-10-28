package com.unirag.rag;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** US3：最终包边界、旧位置残留与 Mapper XML 命名空间的轻量静态检查。 */
class PackageStructureTest {

    private static final Path MAIN_JAVA = Path.of("src/main/java");
    private static final Path MAPPER_XML = Path.of("src/main/resources/mapper");

    @Test
    void 已迁移类不再保留在旧位置且旧包目录不存在() {
        List<Path> movedClasses = List.of(
                MAIN_JAVA.resolve("com/unirag/upload/support/TenantContext.java"),
                MAIN_JAVA.resolve("com/unirag/upload/support/TenantContextInterceptor.java"),
                MAIN_JAVA.resolve("com/unirag/upload/support/WebConfig.java"),
                MAIN_JAVA.resolve("com/unirag/upload/support/ApiResponse.java"),
                MAIN_JAVA.resolve("com/unirag/upload/support/ApiException.java"),
                MAIN_JAVA.resolve("com/unirag/upload/support/GlobalExceptionHandler.java"),
                MAIN_JAVA.resolve("com/unirag/upload/support/SchedulingConfig.java"),
                MAIN_JAVA.resolve("com/unirag/retrieval/QaService.java"),
                MAIN_JAVA.resolve("com/unirag/retrieval/web/QaController.java"),
                MAIN_JAVA.resolve("com/unirag/retrieval/web/AskRequest.java"),
                MAIN_JAVA.resolve("com/unirag/retrieval/web/AskResponse.java"));
        assertThat(movedClasses).allSatisfy(path -> assertThat(path).doesNotExist());

        assertThat(MAIN_JAVA.resolve("com/unirag/infrastructure/mp")).doesNotExist();
        assertThat(MAIN_JAVA.resolve("com/unirag/storage")).doesNotExist();
        assertThat(MAIN_JAVA.resolve("com/unirag/embedding")).doesNotExist();
    }

    @Test
    void common与infrastructure不得反向依赖业务能力包() throws IOException {
        List<String> forbiddenImports = List.of(
                "import com.unirag.tenant.",
                "import com.unirag.ingestion.",
                "import com.unirag.upload.",
                "import com.unirag.retrieval.",
                "import com.unirag.rag.");
        assertNoForbiddenImports(MAIN_JAVA.resolve("com/unirag/common"), forbiddenImports);
        assertNoForbiddenImports(MAIN_JAVA.resolve("com/unirag/infrastructure"), forbiddenImports);
    }

    @Test
    void retrieval不得依赖rag编排包() throws IOException {
        assertNoForbiddenImports(MAIN_JAVA.resolve("com/unirag/retrieval"),
                List.of("import com.unirag.rag."));
    }

    @Test
    void 所有MapperXML使用persistence命名空间且无旧命名空间残留() throws IOException {
        try (Stream<Path> paths = Files.list(MAPPER_XML)) {
            List<Path> xmlFiles = paths.filter(path -> path.getFileName().toString().endsWith(".xml")).toList();
            assertThat(xmlFiles).isNotEmpty();
            for (Path xml : xmlFiles) {
                String content = Files.readString(xml);
                assertThat(content)
                        .as("%s must use persistence namespace", xml)
                        .contains("namespace=\"com.unirag.infrastructure.persistence.mapper.")
                        .doesNotContain("com.unirag.infrastructure.mp");
            }
        }
    }

    private static void assertNoForbiddenImports(Path root, List<String> forbiddenImports) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            List<Path> javaFiles = paths.filter(path -> path.toString().endsWith(".java")).toList();
            assertThat(javaFiles).isNotEmpty();
            for (Path javaFile : javaFiles) {
                String content = Files.readString(javaFile);
                assertThat(forbiddenImports)
                        .as("%s must not contain forbidden imports", javaFile)
                        .allSatisfy(forbidden ->
                                assertThat(content).doesNotContain(forbidden));
            }
        }
    }
}
