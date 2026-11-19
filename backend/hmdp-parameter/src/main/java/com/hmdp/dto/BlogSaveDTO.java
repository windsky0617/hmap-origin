package com.hmdp.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.Arrays;

@Data
public class BlogSaveDTO {

    @NotNull(message = "关联店铺不能为空")
    @Positive(message = "店铺ID必须大于0")
    private Long shopId;

    @NotBlank(message = "标题不能为空")
    @Size(max = 255, message = "标题不能超过255个字符")
    private String title;

    @NotBlank(message = "图片不能为空")
    @Size(max = 2048, message = "图片信息不能超过2048个字符")
    private String images;

    @NotBlank(message = "内容不能为空")
    @Size(max = 2048, message = "内容不能超过2048个字符")
    private String content;

    @AssertTrue(message = "图片应为1至9项，且不能包含空项")
    public boolean isImageListValid() {
        if (images == null || images.isBlank()) {
            return true;
        }
        String[] paths = images.split(",", -1);
        return paths.length <= 9
                && Arrays.stream(paths).noneMatch(String::isBlank);
    }
}