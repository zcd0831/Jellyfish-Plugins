package zcd.jellyfish.plugin.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zcd.jellyfish.api.extension.InputReferenceChoice;
import zcd.jellyfish.api.extension.InputReferenceRequest;
import zcd.jellyfish.api.extension.InputReferenceResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FileReferenceCompletion} 的单元测试：验证路径切分、前缀过滤、目录优先与转义。
 * <p>
 * 用例统一使用<b>绝对路径前缀</b>，因为相对路径的基准是进程工作目录，测试里无法安全地改它；
 * 绝对路径下「目录片段 + 名字前缀」的切分逻辑与相对路径完全一致。
 *
 * @author zcd
 */
@DisplayName("FileReferenceCompletion 引用补全")
class FileReferenceCompletionTest {

    /** 被测处理器。 */
    private final FileReferenceCompletion completion = new FileReferenceCompletion();

    /** 临时目录，充当工作目录的替身。 */
    @TempDir
    Path root;

    @Test
    @DisplayName("空前缀应列出全部子项，且目录排在文件之前、目录名带 /")
    void handle_should_list_entries_with_directories_first() throws Exception {
        // Given
        Files.createDirectories(root.resolve("src"));
        Files.write(root.resolve("readme.md"), "hi".getBytes("UTF-8"));

        // When
        InputReferenceResult result = completion.handle(request(root + "/"));

        // Then
        List<InputReferenceChoice> choices = result.getChoices();
        assertEquals(2, choices.size());
        assertEquals("src/", choices.get(0).getLabel());
        assertEquals(root + "/src/", choices.get(0).getInsertText());
        assertEquals("目录", choices.get(0).getDetail());
        assertEquals("readme.md", choices.get(1).getLabel());
    }

    @Test
    @DisplayName("名字前缀应参与过滤")
    void handle_should_filter_by_name_prefix() throws Exception {
        // Given
        Files.createDirectories(root.resolve("src"));
        Files.createDirectories(root.resolve("test"));
        Files.write(root.resolve("readme.md"), "hi".getBytes("UTF-8"));

        // When
        InputReferenceResult result = completion.handle(request(root + "/s"));

        // Then
        assertEquals(1, result.getChoices().size());
        assertEquals("src/", result.getChoices().get(0).getLabel());
    }

    @Test
    @DisplayName("插入文本应是「目录片段 + 名字」，可继续往下钻")
    void handle_should_prepend_directory_part_to_insert_text() throws Exception {
        // Given
        Files.createDirectories(root.resolve("src").resolve("main"));

        // When
        InputReferenceResult result = completion.handle(request(root + "/src/m"));

        // Then
        assertEquals(1, result.getChoices().size());
        assertEquals("main/", result.getChoices().get(0).getLabel());
        assertEquals(root + "/src/main/", result.getChoices().get(0).getInsertText());
    }

    @Test
    @DisplayName("片段里带转义空格时应还原后再匹配，能接着往下补")
    void handle_should_match_after_unescaping_escaped_whitespace() throws Exception {
        // Given：文件叫「my file.txt」，用户接受补全后输入框里是 @…/my\ file.txt，
        // 接着往下敲成了 @…/my\ f
        Files.write(root.resolve("my file.txt"), "hi".getBytes("UTF-8"));

        // When
        InputReferenceResult result = completion.handle(request(root + "/my\\ f"));

        // Then：必须匹配上（不还原的话永远匹配不上），而插进去的仍是转义过的完整名字
        assertEquals(1, result.getChoices().size());
        assertEquals("my file.txt", result.getChoices().get(0).getLabel());
        assertEquals(root + "/my\\ file.txt", result.getChoices().get(0).getInsertText());
    }

    @Test
    @DisplayName("目录名里有空格时，目录片段也要一起转义")
    void handle_should_escape_directory_part_too() throws Exception {
        // Given：目录名带空格，用户已经进到它里面
        Files.createDirectories(root.resolve("my dir").resolve("sub"));

        // When
        InputReferenceResult result = completion.handle(request(root + "/my\\ dir/s"));

        // Then
        assertEquals(1, result.getChoices().size());
        assertEquals("sub/", result.getChoices().get(0).getLabel());
        assertEquals(root + "/my\\ dir/sub/", result.getChoices().get(0).getInsertText());
    }

    @Test
    @DisplayName("名字里的空格应转义，避免片段被拆成两段")
    void handle_should_escape_spaces_in_insert_text() throws Exception {
        // Given
        Files.write(root.resolve("a b.txt"), "hi".getBytes("UTF-8"));

        // When
        InputReferenceResult result = completion.handle(request(root + "/a"));

        // Then
        assertEquals(1, result.getChoices().size());
        assertEquals("a b.txt", result.getChoices().get(0).getLabel());
        assertEquals(root + "/a\\ b.txt", result.getChoices().get(0).getInsertText());
    }

    @Test
    @DisplayName("没有匹配项时返回空候选，而不是失败")
    void handle_should_return_empty_when_no_match() throws Exception {
        // Given
        Files.write(root.resolve("readme.md"), "hi".getBytes("UTF-8"));

        // When
        InputReferenceResult result = completion.handle(request(root + "/zzz"));

        // Then
        assertTrue(result.getChoices().isEmpty());
    }

    @Test
    @DisplayName("目录不存在时返回空候选")
    void handle_should_return_empty_when_directory_missing() throws Exception {
        // When
        InputReferenceResult result = completion.handle(request(root.resolve("nope") + "/a"));

        // Then
        assertTrue(result.getChoices().isEmpty());
    }

    @Test
    @DisplayName("隐藏文件也应列出：目录里有什么是事实")
    void handle_should_include_hidden_entries() throws Exception {
        // Given
        Files.write(root.resolve(".env"), "x".getBytes("UTF-8"));

        // When
        InputReferenceResult result = completion.handle(request(root + "/"));

        // Then
        assertEquals(1, result.getChoices().size());
        assertEquals(".env", result.getChoices().get(0).getLabel());
    }

    /**
     * 构造一次引用补全请求。
     *
     * @param token 标记之后的片段
     * @return 请求
     */
    private static InputReferenceRequest request(String token) {
        return new InputReferenceRequest("@", token, "@" + token, token.length() + 1, "s-1");
    }
}
